package org.apache.bookkeeper;

import java.util.Random;

public class Dice {
    Random r = new Random();

    void playDice() throws InterruptedException {
        while (true) {
            Thread.sleep(1000);
            System.out.println("Value = " + (r.nextInt(6) + 1));
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Dice d = new Dice();
        d.playDice();
    }
}
