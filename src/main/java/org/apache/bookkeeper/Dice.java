package org.apache.bookkeeper;




import com.google.common.primitives.Ints;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;

import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.BKException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

public class Dice extends LeaderSelectorListenerAdapter implements Closeable {

    final static String ZOOKEEPER_SERVER = "127.0.0.1:2181";
    final static String ELECTION_PATH = "/dice-elect";
    final static byte[] DICE_PASSWD = "dice".getBytes();
    final static String DICE_LOG = "/dice-log";

    Random r = new Random();
    CuratorFramework curator;
    LeaderSelector leaderSelector;
    BookKeeper bookkeeper;

    volatile boolean leader = false;

    Dice() throws Exception {
        curator = CuratorFrameworkFactory.newClient(ZOOKEEPER_SERVER,
                2000, 10000, new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();

        leaderSelector = new LeaderSelector(curator, ELECTION_PATH, this);
        leaderSelector.autoRequeue();
        leaderSelector.start();

        ClientConfiguration conf = new ClientConfiguration()
            .setZkServers(ZOOKEEPER_SERVER).setZkTimeout(30000);
        bookkeeper = new BookKeeper(conf);
    }

    @Override
    public void takeLeadership(CuratorFramework client)
            throws Exception {
        synchronized (this) {
            System.out.println("Becoming leader");
            leader = true;
            try {
                while (true) {
                    this.wait();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                leader = false;
            }
        }
    }

    @Override
    public void close() {
        leaderSelector.close();
        curator.close();
    }

    EntryId lead(EntryId skipPast) throws Exception {
        EntryId lastDisplayedEntry = skipPast;
        Stat stat = new Stat();
        List<Long> ledgers;
        boolean mustCreate = false;
        try {
            byte[] ledgerListBytes = curator.getData()
                .storingStatIn(stat).forPath(DICE_LOG);
            ledgers = listFromBytes(ledgerListBytes);
        } catch (KeeperException.NoNodeException nne) {
            ledgers = new ArrayList<Long>();
            mustCreate = true;
        }

        List<Long> toRead = ledgers;
        if (skipPast.getLedgerId() != -1) {
            toRead = ledgers.subList(ledgers.indexOf(skipPast.getLedgerId()),
                                     ledgers.size());
        }

        long nextEntry = skipPast.getEntryId() + 1;
        for (Long previous : toRead) {
            LedgerHandle lh;
            try {
                lh = bookkeeper.openLedger(previous,
                        BookKeeper.DigestType.MAC, DICE_PASSWD);
            } catch (BKException.BKLedgerRecoveryException e) {
                return lastDisplayedEntry;
            }

            if (nextEntry > lh.getLastAddConfirmed()) {
                nextEntry = 0;
                continue;
            }
            Enumeration<LedgerEntry> entries
                = lh.readEntries(nextEntry, lh.getLastAddConfirmed());

            while (entries.hasMoreElements()) {
                LedgerEntry e = entries.nextElement();
                byte[] entryData = e.getEntry();
                System.out.println("Value = " + Ints.fromByteArray(entryData)
                                   + ", epoch = " + lh.getId()
                                   + ", catchup");
                lastDisplayedEntry = new EntryId(lh.getId(), e.getEntryId());
            }
        }

        LedgerHandle lh = bookkeeper.createLedger(3, 3, 2,
                BookKeeper.DigestType.MAC, DICE_PASSWD);
        ledgers.add(lh.getId());
        byte[] ledgerListBytes = listToBytes(ledgers);
        if (mustCreate) {
            try {
                curator.create().forPath(DICE_LOG, ledgerListBytes);
            } catch (KeeperException.NodeExistsException nne) {
                return lastDisplayedEntry;
            }
        } else {
            try {
                curator.setData()
                    .withVersion(stat.getVersion())
                    .forPath(DICE_LOG, ledgerListBytes);
            } catch (KeeperException.BadVersionException bve) {
                return lastDisplayedEntry;
            }
        }

        try {
            while (leader) {
                Thread.sleep(1000);
                int nextInt = r.nextInt(6) + 1;
                long entryId = lh.addEntry(Ints.toByteArray(nextInt));
                System.out.println("Value = " + nextInt
                                   + ", epoch = " + lh.getId()
                                   + ", leading");
                lastDisplayedEntry = new EntryId(lh.getId(), entryId);
            }
            lh.close();
        } catch (BKException e) {
            // let it fall through to the return
        }
        return lastDisplayedEntry;
    }

    EntryId follow(EntryId skipPast) throws Exception {
        List<Long> ledgers = null;
        while (ledgers == null) {
            try {
                byte[] ledgerListBytes = curator.getData()
                    .forPath(DICE_LOG);
                ledgers = listFromBytes(ledgerListBytes);
                if (skipPast.getLedgerId() != -1) {
                    ledgers = ledgers.subList(ledgers.indexOf(skipPast.getLedgerId()),
                                              ledgers.size());
                }
            } catch (KeeperException.NoNodeException nne) {
                Thread.sleep(1000);
            }
        }

        EntryId lastReadEntry = skipPast;
        while (!leader) {
            for (long previous : ledgers) {
                boolean isClosed = false;
                long nextEntry = 0;
                while (!isClosed && !leader) {
                    if (lastReadEntry.getLedgerId() == previous) {
                        nextEntry = lastReadEntry.getEntryId() + 1;
                    }
                    isClosed = bookkeeper.isClosed(previous);
                    LedgerHandle lh = bookkeeper.openLedgerNoRecovery(previous,
                            BookKeeper.DigestType.MAC, DICE_PASSWD);

                    if (nextEntry <= lh.getLastAddConfirmed()) {
                        Enumeration<LedgerEntry> entries
                            = lh.readEntries(nextEntry,
                                             lh.getLastAddConfirmed());
                        while (entries.hasMoreElements()) {
                            LedgerEntry e = entries.nextElement();
                            byte[] entryData = e.getEntry();
                            System.out.println("Value = " + Ints.fromByteArray(entryData)
                                               + ", epoch = " + lh.getId()
                                               + ", following");
                            lastReadEntry = new EntryId(previous, e.getEntryId());
                        }
                    }
                    if (isClosed) {
                        break;
                    }
                    Thread.sleep(1000);
                }

            }
            byte[] ledgerListBytes = curator.getData()
                .forPath(DICE_LOG);
            ledgers = listFromBytes(ledgerListBytes);
            ledgers = ledgers.subList(ledgers.indexOf(lastReadEntry.getLedgerId())+1, ledgers.size());
        }
        return lastReadEntry;
    }

    void playDice() throws Exception {
        EntryId lastDisplayedEntry = new EntryId(-1, -1);
        while (true) {
            if (leader) {
                lastDisplayedEntry = lead(lastDisplayedEntry);
            } else {
                lastDisplayedEntry = follow(lastDisplayedEntry);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Dice d = new Dice();
        try {
            d.playDice();
        } finally {
            d.close();
        }
    }

    static class EntryId {
        final long ledgerId;
        final long entryId;

        EntryId(long ledgerId, long entryId) {
            this.ledgerId = ledgerId;
            this.entryId = entryId;
        }

        long getLedgerId() {
            return ledgerId;
        }

        long getEntryId() {
            return entryId;
        }
    }

    static byte[] listToBytes(List<Long> ledgerIds) {
        ByteBuffer bb = ByteBuffer.allocate((Long.SIZE*ledgerIds.size())/8);
        for (Long l : ledgerIds) {
            bb.putLong(l);
        }
        return bb.array();
    }

    static List<Long> listFromBytes(byte[] bytes) {
        List<Long> ledgerIds = new ArrayList<Long>();
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        while (bb.remaining() > 0) {
            ledgerIds.add(bb.getLong());
        }
        return ledgerIds;
    }
}
