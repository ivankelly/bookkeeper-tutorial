package org.apache.bookkeeper;

import java.io.Closeable;
import java.util.Random;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;

public class Dice extends LeaderSelectorListenerAdapter implements Closeable {

    final static String ZOOKEEPER_SERVER = "127.0.0.1:2181";
    final static String ELECTION_PATH = "/dice-elect";

    Random r = new Random();
    CuratorFramework curator;
    LeaderSelector leaderSelector;

    volatile boolean leader = false;


    Dice() throws InterruptedException {
        curator = CuratorFrameworkFactory.newClient(ZOOKEEPER_SERVER,
                2000, 10000, new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();

        leaderSelector = new LeaderSelector(curator, ELECTION_PATH, this);
        leaderSelector.autoRequeue();
        leaderSelector.start();
    }

    @Override
    public void takeLeadership(CuratorFramework client)
            throws Exception {
        synchronized (this) {
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

    void playDice() throws InterruptedException {
        while (true) {
            while (leader) {
                Thread.sleep(1000);
                System.out.println("Value = " + (r.nextInt(6) + 1)
                                   + ", isLeader = " + leader);
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Dice d = new Dice();
        try {
            d.playDice();
        } finally {
            d.close();
        }
    }
}
