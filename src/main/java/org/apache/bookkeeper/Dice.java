package org.apache.bookkeeper;


import com.google.common.primitives.Ints;
import java.io.Closeable;

import java.util.Random;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class Dice extends LeaderSelectorListenerAdapter implements Closeable {

    final static String ZOOKEEPER_SERVER = "127.0.0.1:2181";
    final static String ELECTION_PATH = "/dice-elect";
    final static byte[] DICE_PASSWD = "dice".getBytes();

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

    void lead() throws Exception {
        LedgerHandle lh = bookkeeper.createLedger(3, 3, 2,
                BookKeeper.DigestType.MAC, DICE_PASSWD);
        try {
            while (leader) {
                Thread.sleep(1000);
                int nextInt = r.nextInt(6) + 1;
                lh.addEntry(Ints.toByteArray(nextInt));
                System.out.println("Value = " + nextInt
                                   + ", isLeader = " + leader);
            }
        } finally {
            lh.close();
        }
    }

    void playDice() throws Exception {
        while (true) {
            if (leader) {
                lead();
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
}
