This tutorial aims to show you how to build a replicated distributed system using bookkeeper as the replicated log. Before we start, you will need to have a bookkeeper cluster up and running. You can download the bookkeeper distribution at https://zookeeper.apache.org/bookkeeper/releases.html. The binary distribution, bookkeeper-server-4.x.x-bin.tar.gz, will be sufficient for the tutorial.
This tutorial does not cover the setup of a distributed cluster, but you can run a local cluster on your machine by running:

```
$ bookkeeper-server/bin/bookkeeper localbookie 6
```

This will start up a local zookeeper instance with 6 bookie servers. Any data written to this cluster will be removed when you kill the process.

The code for this tutorial is available at https://github.com/ivankelly/bookkeeper-tutorial/. Each section has a link with points to a tag for the completed code for that section.

# The base application

[(full code)](https://github.com/ivankelly/bookkeeper-tutorial/tree/basic)

We have a dice application. It generates a new number between 1 and 6 every second. 

```java
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
```

We want to make sure that multiple instances of this application, possibly running on separate machines will all see the same exact sequence of random numbers, even in the case of some machines becoming unavailable. This tutorial will show you how.

To start, download the base application, compile and run it.
```
$ git clone https://github.com/ivankelly/bookkeeper-tutorial.git
$ mvn package
$ mvn exec:java -Dexec.mainClass=org.apache.bookkeeper.Dice
[INFO] Scanning for projects...
[INFO]                                                                         
[INFO] ------------------------------------------------------------------------
[INFO] Building tutorial 1.0-SNAPSHOT
[INFO] ------------------------------------------------------------------------
[INFO] 
[INFO] --- exec-maven-plugin:1.3.2:java (default-cli) @ tutorial ---
[WARNING] Warning: killAfter is now deprecated. Do you need it ? Please comment on MEXEC-6.
Value = 4
Value = 5
Value = 3
...
...
```


# Leaders and followers (and a little bit of background)

To achieve this common view in multiple instances of the program, we need each instance to agree on what the next number in the sequence will be. For example, the instances must agree that 4 is the first number and 2 is the second number and 5 is the third number and so on. This is a difficult problem, especially in the case that any instance may go away at any time, and messages between the instances can be lost or reordered.

Luckily, there are already algorithms to solve this. [Paxos](http://en.wikipedia.org/wiki/Paxos_%28computer_science%29) is an abstract algorithm to implement this kind of agreement, while [Zab](http://zookeeper.apache.org) and [Raft](http://en.wikipedia.org/wiki/Raft_%28computer_science%29) are more practical protocols. [This video](https://www.youtube.com/watch?v=JEpsBg0AO6o) gives a good overview about how these algorithms usually look. They all have a similar core.

It would be possible to run the Paxos to agree on each number in the sequence. Running Paxos each time can be expensive. What Zab and Raft do is that they use a Paxos-like algorithm to elect a leader. The leader then decides what the sequence of events should be, in a log, which the other instances can then follow to maintain the same state as the leader.

Bookkeeper provides the functionality for the second part of the protocol, allowing a leader to write a log and have multiple follower tailing the log. Bookkeeper does not do leader election. You will need a zookeeper or raft instance for that.


## Why not just use zookeeper for everything?
There are a number of reasons:
 1. Zookeeper’s log is only exposed through a tree like interface. It can be hard to shoehorn your application into this. 
 2. A zookeeper ensemble of multiple machines is limited to one log. You may want one log per resource, which will become expensive very quickly.
 3. Adding extra machines to a zookeeper ensemble does not increase capacity nor throughput.

Bookkeeper can be viewed as a means of exposing zookeeper replicated log to applications in a scalable fashion. We still use zookeeper to maintain consistency guarantees.

**TL;DR You need to elect a leader instance**

# Electing a leader

[(full code)](https://github.com/ivankelly/bookkeeper-tutorial/tree/election)

We’ll use zookeeper to elect a leader. A zookeeper instance will have started locally when you started the localbookie application above. To verify it’s running, run the following command.

```
$ echo stat | nc localhost 2181
Zookeeper version: 3.4.6-1569965, built on 02/20/2014 09:09 GMT
Clients:
 /127.0.0.1:59343[1](queued=0,recved=40,sent=41)
 /127.0.0.1:49354[1](queued=0,recved=11,sent=11)
 /127.0.0.1:49361[0](queued=0,recved=1,sent=0)
 /127.0.0.1:59344[1](queued=0,recved=38,sent=39)
 /127.0.0.1:59345[1](queued=0,recved=38,sent=39)
 /127.0.0.1:59346[1](queued=0,recved=38,sent=39)

Latency min/avg/max: 0/0/23
Received: 167
Sent: 170
Connections: 6
Outstanding: 0
Zxid: 0x11
Mode: standalone
Node count: 16
```

To interact with zookeeper, we’ll use the curator recipes library rather than the stock zookeeper one. Getting things right with the zookeeper client can be tricky, and curator removes a lot of the pointy corners for you. In fact, curator even provides a leader election recipe, so we need to do very little work.

```java
public class Dice extends LeaderSelectorListenerAdapter implements Closeable {

    final static String ZOOKEEPER_SERVER = "127.0.0.1:2181";
    final static String ELECTION_PATH = "/dice-elect";

    ...

    Dice() throws InterruptedException {
        curator = CuratorFrameworkFactory.newClient(ZOOKEEPER_SERVER,
                2000, 10000, new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();

        leaderSelector = new LeaderSelector(curator, ELECTION_PATH, this);
        leaderSelector.autoRequeue();
        leaderSelector.start();
    }
```

In the constructor for Dice, we need to create the curator client. We specify four things when creating the client, the location of the zookeeper service, the session timeout, the connect timeout and the retry policy. The session timeout is the zookeeper concept. If the zookeeper server doesn’t hear anything from the client for this amount of time, any leases which the client has will be timed out. This is important in leader election. For leader election, the curator client will take a lease out on ELECTION_PATH. The first instance to take the lease will become leader and the rest will become followers, but their claim on the lease will remain in the cue. If the first instance then goes away, once the session times out, the lease will be released and the next instance in the queue will become the leader. The call to #autoRequeue() will make the client queue itself again if it loses the lease for some other reason, such as if it was still alive, but it a garbage collection cycle caused it to lose its session, and thereby its lease. I’ve set the lease to be quite low so that when we test out leader election, transitions will be quite quick. The optimum length for session timeout depends very much on the use case. The other parameters are the connection timeout, i.e. the amount of time it will spend trying to connect to a zookeeper server before giving up, and the retry policy. The retry policy specifies how the client should respond to transient errors, such as connection loss. Operations that fail with transient errors can be retried, and this argument specifies how often the retries should occur.

Finally, you’ll have noticed that Dice now extends LeaderSelectorListenerAdapter and implements Closeable. Closeable is there to close the resource we have initialized in the constructor, the curator client and the leaderSelector. LeaderSelectorListenerAdapter is a callback that the leaderSelector uses to notify the instance that it is now the leader. It is passed as the third argument to LeaderSelector().

```java
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
```

takeLeadership is the callback called by LeaderSelector when the instance is leader. It should only return when the instance wants to give up leadership. In our case, we never do so we wait on the current object until we’re interrupted. To signal to the rest of the program that we are leader we set a volatile boolean called leader to true. This is unset after we are interrupted.

```java
    void playDice() throws InterruptedException {
        while (true) {
            while (leader) {
                Thread.sleep(1000);
                System.out.println("Value = " + (r.nextInt(6) + 1)
                                   + ", isLeader = " + leader);
            }
        }
    }
```

Finally we modify #playDice() to only generate random numbers when it is the leader.

Run two instances of the program in two different terminals. You’ll see that one becomes leader and prints numbers and the other just sits there. Now stop the leader using Control-Z. This will pause the process, but it won’t kill it. You will be dropped back to the shell in that terminal. After a couple of seconds, you will see that the other instance has become the leader. Zookeeper will guarantee that only one instance is selected as leader at any time.

Now go back to the shell that the original leader was on and wake up the process using fg. You’ll see something like the following:

```
...
...
Value = 4, isLeader = true
Value = 4, isLeader = true
^Z
[1]+  Stopped                 mvn exec:java -Dexec.mainClass=org.apache.bookkeeper.Dice
$ fg
mvn exec:java -Dexec.mainClass=org.apache.bookkeeper.Dice
Value = 3, isLeader = true
Value = 1, isLeader = false
```

Whats this!?! The other instance is leader, but this instance first of all thinks it is leader and generates a number, and then generates a number even though it knows it is not leader. In fact this is perfectly natural. The leader election happens on zookeeper, but it takes time changes in the leader to be propagated to all instances. So a race occurs where an instance thinks it is the leader while zookeeper thinks otherwise.

To solve this problem we need to some way to prevent previous leaders from continuing to think they are leader. Sending a message to the previous leader isn’t an option. Messages may get lost or delayed or the previous leader may be temporarily down. Another way is to use a shared log. All updates are written to the shared log before being applied. A new leader can tell this log to block writes from previous leaders. This is exactly what bookkeeper does.

# Writing to the log

[(full code)](https://github.com/ivankelly/bookkeeper-tutorial/tree/storing)

Before we get into the business of blocking previous leaders from writing we need to first implement the logic for writing to the log.

```java
    Dice() throws Exception {
    	...

        ClientConfiguration conf = new ClientConfiguration()
            .setZkServers(ZOOKEEPER_SERVER).setZkTimeout(30000);
        bookkeeper = new BookKeeper(conf);
    }

```

We construct the bookkeeper client in the Dice constructor and configure the zookeeper server and zookeeper session timeout that it should use. The zookeeper session timeout can be quite large for bookkeeper, as it doesn’t use anything that depends on the session timeout logic. The bookkeeper client should also be closed in Dice#close.

```java
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
```

When we become the leader, we create a new ledger. A ledger is the basic unit of bookkeeper. It can be thought of as a segment of a larger log. In this case we are only creating a single ledger, but later we will be creating multiple ledgers and connecting them together to create a shared log. For now, we just want to get data into a ledger.

The ledger is created with a 3-3-2 configuration. These are the ensemble, the write quorum and the ack quorum. The ensemble is the number of bookies the data in the ledger will be stored on. All entries may not be stored on all bookies if the ensemble is larger than the write quorum. The write quorum is the number of bookies each entry is written to. The ack quorum is the number of bookies we must get a response from before we acknowledge the write to the client. In this case, there are 3 bookies, we write to all 3 every time, but we acknowledge to the client when we've received a response from 2. If the ensemble is larger than the write quorum, then entries will be striped across the bookies.

The digest type and password are for checksumming. They prevent clients from overwriting each others data in a misconfigured system. They're actually unnecessary, but the client api requires them.

Once the ledger is created we can write to it. #addEntry will append an entry onto the end of the ledger. Entries are byte arrays, so we convert the randomly generated integer into a byte array, using guava's Ints utility, before adding it to the ledger.

Once we are finished with a ledger we must close it. This is actually an important step and it fixes the content of the ledger. From this point on the ledger is immutable. It cannot be reopened for writing and its contents cannot be modified.

Of course, we don't save a reference to the ledger anywhere, so once we have written it, noone else can ever access it, even to read it. This is what we will deal with in the next section.

# Making the log available to others

[(full code)](https://github.com/ivankelly/bookkeeper-tutorial/tree/sharing)

Previously we have written to a single ledger. However, we have not provided a way to share this between instances. What's more, as a ledger is immutable, each leader will have to create its own ledger. So ultimately, when the application has run for a while, having changed leaders multiple times, we will end up with a list of ledgers. This list of ledgers represents the log of the application. Any new instance can print the same output as any preexisting instance by simply reading this log.

This list of logs need to be shared among all instances of the program. For this we will use zookeeper. 

```java
public class Dice extends LeaderSelectorListenerAdapter implements Closeable {
    ...

    final static String DICE_LOG = "/dice-log";

```

We define the path of the zookeeper znode in which we want to store the log. A znode in zookeeper is like a file. You can write and read byte arrays from a znode. However, the contents of a znode must be written and read as a whole, so it's best to only store small pieces of data there. Each time a znode is updated, a new version is assigned. This can be used for check-and-set operations, which is important to avoid race conditions in distributed systems.

```java
    void lead() throws Exception {
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
        for (Long previous : ledgers) {
            LedgerHandle lh;
            try {
                lh = bookkeeper.openLedger(previous,
                        BookKeeper.DigestType.MAC, DICE_PASSWD);
            } catch (BKException.BKLedgerRecoveryException e) {
                return;
            }
            Enumeration<LedgerEntry> entries
                = lh.readEntries(0, lh.getLastAddConfirmed());

            while (entries.hasMoreElements()) {
                byte[] entryData = entries.nextElement().getEntry();
                System.out.println("Value = " + Ints.fromByteArray(entryData)
                                   + ", epoch = " + lh.getId()
                                   + ", catchup");
            }
        }
```

We read the list of ledgers from DICE_LOG and store the version in stat. As the list of ledgers is in byte form, we need to convert into a java list. If this is the first time running, there will be no list of ledgers, and therefore no znode containing them. In this case a NoNodeException will occur. We take note of this, as it affects how will will update the list later.

Once we have the list, we loop through them, opening the ledgers and printing their contents. It's important to note that the default open operation in bookkeeper is a fencing open. In a fencing open, anyone who is writing to the ledger will receive an exception when they try to write again. This is how we exclude other leaders.

```java
    void lead() throws Exception {
        ...

        LedgerHandle lh = bookkeeper.createLedger(3, 3, 2,
                BookKeeper.DigestType.MAC, DICE_PASSWD);
        ledgers.add(lh.getId());
        byte[] ledgerListBytes = listToBytes(ledgers);
        if (mustCreate) {
            try {
                curator.create().forPath(DICE_LOG, ledgerListBytes);
            } catch (KeeperException.NodeExistsException nne) {
                return;
            }
        } else {
            try {
                curator.setData()
                    .withVersion(stat.getVersion())
                    .forPath(DICE_LOG, ledgerListBytes);
            } catch (KeeperException.BadVersionException bve) {
                return;
            }
        }

        try {
            while (leader) {
                Thread.sleep(1000);
                int nextInt = r.nextInt(6) + 1;
                lh.addEntry(Ints.toByteArray(nextInt));
                System.out.println("Value = " + nextInt
                                   + ", epoch = " + lh.getId()
                                   + ", leading");
            }
            lh.close();
        } catch (BKException e) {
            return;
        }
    }
```

Once we have read all the previous ledgers, we create a new one and add it to the list. We must make sure this list is updated before writing to the ledger to avoid losing data. If #create() or #setData() throw an exception, it means that someone is trying to update the list concurrently. We must examine if we are still leader, and try again if we are. The retry is handled by the loop in #playDice().

We can then write to the ledger as before. However, now we have to take care to handle the BKException. If we receive an exception, it may mean that someone has fenced the ledger we are writing to. This means that someone else has opened it using #openLedger, so they must think that they are the leader. Like in the case of concurrent modifications to the ledger list, we must examine if we are still leader and then try again if so.

Run a couple on instance of this on your machine. You'll see that when the leader changes, it will print out the history of what was written by previous leaders. However, we have a bug! When a leader changes, it will print out the whole history, even if it has been leader before. We need to keep track of which updates we have seen. 

# Tracking the updates

[(full code)](https://github.com/ivankelly/bookkeeper-tutorial/tree/tracking)

Tracking the updates is fairly simple. We just need to keep a record of the last thing we printed, and skip past it any time we become leader. 

```java
    EntryId lead(EntryId skipPast) throws Exception {
        EntryId lastDisplayedEntry = skipPast;
```

The signature for #lead() needs to change so that the last displayed update is passed between different invokations. EntryId is a simple data structure, inside which we can store the ledger id and the entry id of the last update we have displayed.

```java
    EntryId lead(EntryId skipPast) throws Exception {
        ...
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
        ...
```

The algorithm for reading also changes. Instead of iterating through all the ledgers in the list we only iterate through any ledger which is greater to or equal to the ledger of the last displayed entry. We also skip past the entry id of the last displayed entry when calling #readEntries. The only special case we need to handle is if the last displayed entry is the last entry of a ledger. In this case, we set nextEntry to zero, and skip to the next ledger.

Any time we do read an entry and display it, we update the last displayed entry to reflect this.

```java
    EntryId lead(EntryId skipPast) throws Exception {
        ...

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
```

Finally, we also update the last displayed entry any time we add a new entry to the log. With this change, new leaders will only print numbers which they haven't seen before. You can test this for yourself. Run two instances of the program. Stop the leader with Control-Z, and once the other instance has become leader, resume the first one (fg). Then kill the second leader. When the first leader becomes leader again, it will only print the number which it missed.

# Tailing the log

[(full code)](https://github.com/ivankelly/bookkeeper-tutorial/tree/tailing)

Of course, it would be nicer if the followers could keep up to date with the leader in the background without having to wait to become leaders themselves. To do this we need to tail the log. For the most part this is very similar to how we read the previous ledgers when we become leader. However, how we open the ledgers is different. When we open the ledgers as leader, we need to ensure that no other instance can write to the ledgers from that point onwards. Therefore, we use a fencing open, which is the default #openLedger call in Bookkeeper. However, for tailing the log, we don't want to stop the leader from writing new updates, so we use a non-fenching open, which is the #openLedgerNoRecovery call in Bookkeeper.

First we must modify #playDice to go into a following state when we're not the leader.
```java
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
```

```java
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
```

The first part of following is almost identical to leading. We read the list of ledgers from zookeeper and trim the list to only include ledgers which we have displayed already. A thing to note here, is that if we go into following mode during the first run of the application, and the leader hasn't created the list of ledgers in zookeeper yet we will get an exception. If this occurs we try again after 1 second.

Once we have the list, we go into the main tailing loop.

```java
    EntryId follow(EntryId skipPast) throws Exception {
        ...

        EntryId lastReadEntry = skipPast;
        while (!leader) {
            for (long previous : ledgers) {
                ...
            }
            byte[] ledgerListBytes = curator.getData()
                .forPath(DICE_LOG);
            ledgers = listFromBytes(ledgerListBytes);
            ledgers = ledgers.subList(ledgers.indexOf(lastReadEntry.getLedgerId())+1,
                                      ledgers.size());
        }
        return lastReadEntry;
    }
```

While we are still leader, we loop over all ledgers in the ledgers list, printing their content. Once we have finished with the current list of ledgers, we check zookeeper to see if any new ledgers have been added to the list. This looks like it would run in a tight loop, but that is not the case. Ledger reading loop will wait until the last ledger in the list is closed before exiting the loop. When the last ledger in the list is closed, it means that the leader must have changed, so there must be a new ledger in the list to read.

```java
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
                        ... // read all entries from nextEntry to last add confirmed
                    }
                    if (isClosed) {
                        break;
                    }
                    Thread.sleep(1000);
                } 
            }
```

For each ledger we enter into an inner loop. First we check if the ledger has been closed. If so, once we have read all the entries that we can, we need to reopen the ledger to check for any new entries. We continue like this until the ledger is either closed, or we become leader.

Note that we are using #openLedgerNoRecovery here. The value returned by last add confirmed will change after each opening if there are new entries which can be read. The last add confirmed is a variable maintained by the leader. It is the last entry written for which it has received an ack quorum of acknowledgements. In our case, this means that the entry has been acknowledged on at least 2 bookies. It also guarantees that each entry before it in that ledger has been acknowledged on 2 bookies.

Once we have read all entries, we check isClosed to see if we need to check this ledger again. If not, we break out of the loop and move onto the next ledger. Otherwise we wait a second and try again.

# Wrap up

Now you have a fully distributed dice application. Not very useful, but it should give you some idea of what is required to make an application fault tolerant without losing consistency. Play around with the application. Run many instances. Kill a few leaders. You will always see the same sequence of number printed to the screen. If not, then you have found a bug, please let us know.

# What's next?

The dice application we've just written is pretty useless. But the principles contained therein could be used to replicate pretty much any service. Imagine a simple key value store. This could be made replicated by adding all create, put and delete operations to a replicated log. Multiple logs could be used if you want to shard your store across many servers. There are many possibilities.

However, this tutorial doesn't address some issues that would be important in a real implementation. For starters, the log of the dice application will keep growing forever, eventually filling up all your disks and grinding you to a halt. Avoiding this problem depends on your individual usecase. For example, if you have a key value store, you can take a snapshot of the store every so often, and then trim the start of the log to remove anything that had been applied by the time the snapshot was taken. Trimming simply means removing ledgers from the start of the ledger list. For a messaging application, you could keep a record of what each subscriber has consumed and then trim the log based on that.

Note that the tutorial application only uses synchronous APIs. The bookkeeper client does also have asynchronous apis, which allow for higher throughput when writing, but you have to manage your state more carefully.
