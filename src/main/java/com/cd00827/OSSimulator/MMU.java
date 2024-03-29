package com.cd00827.OSSimulator;

import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Memory management unit.<br>
 * Maintains an array representing physical RAM, and allocates memory to processes using a paging system.
 * Allows for processes to be swapped out to text files when memory is full.
 * Provides read and write access to memory.
 *
 * @author cd00827
 */
public class MMU implements Runnable {
    private final String[] ram;
    private final int pageSize;
    private final int pageNumber;
    //Map pid to a map of page number to frame offset
    private final Map<Integer, Map<Integer, Integer>> pageTable;
    //Keep a record of allocated frames
    private final Map<Integer, Boolean> frameAllocationRecord;
    private final Mailbox mailbox;
    private final double clockSpeed;
    private final ObservableList<String> log;
    private final ReentrantLock swapLock;
    private final List<PCB> swappable;

    /**
     * Constructor
     * @param pageSize Size of memory pages in blocks
     * @param pageNumber Number of pages, multiplied by pageSize to get the size of physical memory
     * @param clockSpeed Number of operations to perform per second
     * @param mailbox Mailbox to control this MMU with
     * @param log Log to output messages to
     * @param swapLock Lock to use for synchronising swap operations
     * @param swappable List to use for getting the currently swappable processes
     */
    public MMU(int pageSize, int pageNumber, double clockSpeed, Mailbox mailbox, ObservableList<String> log, ReentrantLock swapLock, List<PCB> swappable) {
        this.ram = new String[pageSize * pageNumber];
        this.pageSize = pageSize;
        this.pageNumber = pageNumber;
        this.clockSpeed = clockSpeed;
        this.mailbox = mailbox;
        this.pageTable = new TreeMap<>();
        this.frameAllocationRecord = new TreeMap<>();
        for (int page = 0; page < pageNumber; page++) {
            frameAllocationRecord.put(page * pageSize, false);
        }
        this.log = log;
        this.swapLock = swapLock;
        this.swappable = swappable;
    }

    /**
     * Write a message to the log
     * @param message Message
     */
    private void log(String message) {
        Platform.runLater(() -> this.log.add(message));
    }

    /**
     * Entry point when starting the MMU thread
     */
    @Override
    public void run() {
        while (true) {
            //Get next command
            Message message = this.mailbox.get(Mailbox.MMU);
            if (message != null) {
                String[] command = message.getCommand();
                switch (command[0]) {

                    //allocate [pid] [blocks] [loading]
                    case "allocate": {
                        int pid = Integer.parseInt(command[1]);
                        int blocks = Integer.parseInt(command[2]);
                        boolean loading = Boolean.parseBoolean(command[3]);
                        boolean done = false;
                        boolean swapping = false;
                        int swapIndex = 0;

                        //Allocate memory
                        while (!done) {
                            switch (this.allocate(pid, blocks)) {
                                //Success, unblock process
                                case 1:
                                    if (loading) {
                                        this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "allocated|" + pid);
                                    }
                                    else {
                                        this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "unblock|" + pid);
                                    }
                                    this.log("[MMU] Allocated " + blocks + " blocks to PID " + pid);
                                    done = true;
                                    break;

                                //Must free up memory and try again
                                case -1:
                                    //If this is the first swap operation, acquire swap lock or wait until available
                                    if (!swapping) {
                                        swapping = true;
                                        this.swapLock.lock();
                                    }
                                    PCB process = null;
                                    try {
                                        process = this.swappable.get(swapIndex);
                                    }
                                    catch (IndexOutOfBoundsException ignored) {}

                                    if (process == null) {
                                        //There is enough memory in the system, but no processes are available to swap
                                        //Tell scheduler to skip this process
                                        this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "skip|" + pid);
                                        this.log("[MMU] Could not swap out enough processes to allocate for PID " + pid + ", skipping");
                                        done = true;
                                    }
                                    else {
                                        //Swap out process and notify scheduler
                                        this.swapOut(process.getPid());
                                        swapIndex++;
                                        this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "swappedOut|" + process.getPid());
                                        this.log("[MMU] Swapped out PID " + process.getPid());
                                    }
                                    break;

                                //Not enough total system memory - drop the process
                                case -2:
                                    this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "drop|" + pid);
                                    this.log("[MMU/ERROR] Out of memory for PID " + pid);
                                    //Break out of loop as nothing more can be done
                                    done = true;
                                    break;
                            }
                        }
                        //If swapping occurred, release swap lock so scheduler can run again
                        if (swapping) {
                            this.swapLock.unlock();
                        }
                    }
                    break;

                    //free [pid] [blocks]
                    case "free": {
                        int pid = Integer.parseInt(command[1]);
                        int blocks = Integer.parseInt(command[2]);

                        if (this.free(pid, blocks)) {
                            this.log("[MMU] Freed "+ blocks + " blocks from PID " + pid);
                        }
                        else{
                            //Process has caused an error, so drop it
                            this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "drop|" + pid);
                            this.log("[MMU/ERROR] PID " + pid + " attempted to free more memory than allocated to it");
                        }
                    }
                    break;

                    //swapIn [pid]
                    case "swapIn": {
                        int pid = Integer.parseInt(command[1]);
                        //If there is enough memory to swap in process, do it and notify scheduler.
                        //Otherwise tell scheduler to skip this process
                        if (this.swapIn(pid)) {
                            this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "swappedIn|" + pid);
                            this.log("[MMU] Swapped in PID " + pid);
                        }
                        else {
                            this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "skip|" + pid);
                            this.log("[MMU] Not enough free memory to swap in PID " + pid + ", skipping");
                        }
                    }
                    break;

                    //read [pid] [address] [final]
                    case "read": {
                        int pid = Integer.parseInt(command[1]);
                        int address = Integer.parseInt(command[2]);
                        String[] data = this.read(pid, address);
                        //If read is successful, send data to whatever requested it, otherwise drop the process
                        if (data[0].equals("success")) {
                            //Unblock process if this was the final read operation
                            if (Boolean.parseBoolean(command[3])) {
                                this.mailbox.put(Mailbox.MMU, message.getSender(), "data|" + data[1] + "|true");
                                this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "unblock|" + pid);
                            }
                            else {
                                this.mailbox.put(Mailbox.MMU, message.getSender(), "data|" + data[1] + "|false");
                            }
                            this.log("[MMU] Read '" + data[1] + "' from virtual address " + address + " for PID " + pid);
                        }
                        //Drop process if write causes an error
                        else {
                            this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "drop|" + pid);
                            this.log("[MMU/ERROR] PID " + pid + " attempted to read from an invalid address");
                        }
                    }
                    break;

                    //write [pid] [address] [data] [final]
                    case "write": {
                        int pid = Integer.parseInt(command[1]);
                        int address = Integer.parseInt(command[2]);
                        String data = command[3];
                        if (this.write(pid, address, data)) {
                            //Unblock process if this was the final write operation
                            if (Boolean.parseBoolean(command[4])) {
                                this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "unblock|" + pid);
                            }
                            this.log("[MMU] Wrote '" + data + "' to virtual address " + address + " for PID " + pid);
                        }
                        else {
                            this.mailbox.put(Mailbox.MMU, Mailbox.SCHEDULER, "drop|" + pid);
                            this.log("[MMU/ERROR] PID " + pid + " attempted to write to an invalid address");
                        }
                    }
                    break;

                    //drop [pid]
                    case "drop": {
                        int pid = Integer.parseInt(command[1]);
                        this.flushProcess(pid);
                        this.log("[MMU] Dropped PID " + pid);
                    }

                    break;
                }
            }

            //Wait for next clock cycle
            try {
                Thread.sleep((long)((1/clockSpeed) * 1000));
            }
            catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Read from a virtual address
     * @param pid PID of process
     * @param address Virtual address to read
     * @return [status, data]<br>
     *     Status is either "success" or "error"
     */
    private String[] read(int pid, int address) {
        int page = address / this.pageSize;
        int offset = address % this.pageSize;
        //Check process has access to address
        if (this.pageTable.get(pid).containsKey(page)) {
            try {
                //Return data
                String value = this.ram[this.pageTable.get(pid).get(page) + offset];
                return new String[] {"success", value};
            }
            //Catch exception if address is null
            catch (NullPointerException e) {
                return new String[] {"error", "null"};
            }
        }
        return new String[] {"error", "null"};
    }

    /**
     * Write to virtual address
     * @param pid PID of process
     * @param address Virtual address to write to
     * @param data Data to write
     * @return True if successful, false if process does not have access to requested address
     */
    private boolean write(int pid, int address, String data) {
        int page = address / this.pageSize;
        int offset = address % this.pageSize;
        if (this.pageTable.get(pid).containsKey(page)) {
            this.ram[this.pageTable.get(pid).get(page) + offset] = data;
            return true;
        }
        return false;
    }

    /**
     * Allocate memory to a process
     * @param pid PID of process
     * @param blocks Number of blocks to allocate
     * @return 1: Success<br>
     * -1: Not enough free memory<br>
     * -2: Tried to allocate more memory than available to the system<br>
     */
    private int allocate(int pid, int blocks) {
        int pages = (int)Math.ceil((double)blocks / this.pageSize);
        int freePages = 0;
        int currentPages = 0;

        //Find out how much memory the process is already using
        if (this.pageTable.containsKey(pid)) {
            for (Map.Entry<Integer, Integer> entry : this.pageTable.get(pid).entrySet()) {
                currentPages++;
            }
        }

        //Check that the system has enough memory
        if (pages + currentPages > this.pageNumber) {
            return -2;
        }

        //Check that there is enough free memory
        for (Map.Entry<Integer, Boolean> entry : this.frameAllocationRecord.entrySet()) {
            if (!entry.getValue()) {
                freePages++;
            }
        }
        if (freePages < pages) {
            return -1;
        }

        //Allocate pages
        int allocatedPages = 0;
        //Iterate over all frames
        for (Map.Entry<Integer, Boolean> entry : this.frameAllocationRecord.entrySet()) {
            //Check if frame is free
            if (!entry.getValue()) {
                //Allocate frame
                this.frameAllocationRecord.put(entry.getKey(), true);
                //Check a map for this process exists
                if (!this.pageTable.containsKey(pid)) {
                    this.pageTable.put(pid, new TreeMap<>());
                }
                //Add mapping to page table
                this.pageTable.get(pid).put(currentPages + allocatedPages, entry.getKey());
                //Break out of loop if done allocating
                allocatedPages++;
                if (allocatedPages == pages) {
                    break;
                }
            }
        }
        return 1;
    }

    /**
     * Free a process's memory
     * @param pid PID of process
     * @param blocks Number of blocks to free
     * @return True: Success<br>
     *     False: Attempted to free more memory than allocated<br>
     */
    private boolean free(int pid, int blocks) {
        int pages = (int)Math.ceil((double)blocks / this.pageSize);

        //Check that process has enough pages allocated
        if (this.pageTable.get(pid) == null) {
            return false;
        }
        else if (this.pageTable.get(pid).size() < pages) {
            return false;
        }

        //Free pages from most to least recently allocated
        int size = this.pageTable.get(pid).size();
        for (int i = 1; i <= pages; i++) {
            //Clear memory to prevent bugs when executing code from reused parts of memory
            for (int j = 0; j < this.pageSize; j++) {
                this.ram[this.pageTable.get(pid).get(size - i) + j] = null;
            }
            this.frameAllocationRecord.put(this.pageTable.get(pid).get(size - i), false);
            this.pageTable.get(pid).remove(size - i);
        }
        return true;
    }

    /**
     * Free all memory allocated to a process
     * @param pid PID of process to flush
     */
    private void flushProcess(int pid) {
        int blocks = this.pageTable.get(pid).size() * this.pageSize;
        //No error handling should be needed as method calculates memory to free using page table
        this.free(pid, blocks);
    }

    /**
     * Swap a process's memory out to a file.<br>
     * Will throw a RuntimeException if swapping fails, as an inability to swap will prevent the simulator from
     * functioning correctly.
     * @param pid PID of process to swap out
     */
    private void swapOut(int pid) {
        File dir = new File("swap");
        File file = new File("swap", pid + ".txt");
        try {
            //Create swap directory
            if (!dir.exists()) {
                Files.createDirectory(dir.toPath());
            }
            //Write contents of memory to file
            Files.deleteIfExists(file.toPath());
            BufferedWriter writer = new BufferedWriter(new FileWriter(file));
            for (Map.Entry<Integer, Integer> page : this.pageTable.get(pid).entrySet()) {
                for (int i = 0; i < this.pageSize; i++) {
                    if (this.ram[page.getValue() + i] != null) {
                        writer.write(this.ram[page.getValue() + i]);
                    }
                    writer.newLine();
                }
            }
            writer.close();
            this.flushProcess(pid);
        }
        catch (Exception e){
            e.printStackTrace();
            this.log("MMU/FATAL] Swapping out PID " + pid + " failed, check you have r/w access to /swap");
            throw new RuntimeException("MMU/FATAL] Swapping out PID " + pid + " failed, check you have r/w access to /swap");
        }
    }

    /**
     * Swap a process's memory in from a file.<br>
     * Will throw a RuntimeException if opening the swap file fails, as this will prevent the simulator from running
     * properly and is likely caused by incorrect permissions
     * @param pid PID of process to swap in
     * @return True: Success<br>
     *     False: Could not allocate enough memory<br>
     */
    private boolean swapIn(int pid) {
        File file = new File("swap", pid + ".txt");
        try {
            //Get required memory
            Stream<String> stream = Files.lines(file.toPath());
            int blocks = (int)stream.count();
            stream.close();
            if (this.allocate(pid, blocks) < 0) {
                //Failed to allocate enough memory.
                //Case where there is not enough total system memory can be ignored, as if that was the case
                //the process would never have existed in memory to be swapped out.
                return false;
            }

            //Load data into memory
            BufferedReader reader = new BufferedReader(new FileReader(file));
            for (int i = 0; i < blocks; i++) {
                String line = reader.readLine();
                if (!line.trim().isEmpty()) {
                    this.write(pid, i, line);
                }
            }
            reader.close();
        }
        catch (IOException e) {
            e.printStackTrace();
            this.log("[MMU/FATAL] Swapping in PID " + pid + " failed, check you have r/w access to /swap");
            throw new RuntimeException("[MMU/FATAL] Swapping in PID " + pid + " failed, check you have r/w access to /swap");
        }
        return true;
    }
}
