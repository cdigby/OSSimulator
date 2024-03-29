package com.cd00827.OSSimulator;

import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CPU, executes instructions for a process provided to it by the scheduler.<br>
 * Each cycle, the CPU will either execute an instruction, or get the data required from memory to execute the current
 * instruction
 * @author cd00827
 */
public class CPU implements Runnable {
    private PCB process;
    private final Scheduler scheduler;
    private final double clockSpeed;
    private final Mailbox mailbox;
    private final ObservableList<String> trace;
    private final ObservableList<String> output;
    private final Deque<String> dataBuffer;
    //Cache of each process's current instruction
    private final Map<Integer, String> instructionCache;
    //Cache of each process's defined variables
    private final Map<Integer, Map<String, Integer>> varCache;
    //Cache of each process's defined labels
    private final Map<Integer, Map<String, Integer>> labelCache;
    private List<String> mathVars;
    //The BufferedWriter each process uses to write its outputs to a file
    private final Map<Integer, BufferedWriter> outputs;

    /**
     * Constructor
     * @param scheduler Reference to scheduler governing this CPU
     * @param mailbox Mailbox to control this CPU with
     * @param clockSpeed Number of instructions to execute per second
     * @param trace Log to output the execution trace to
     * @param output Log to output messages and the result of processes to
     */
    public CPU(Scheduler scheduler, Mailbox mailbox, double clockSpeed, ObservableList<String> trace, ObservableList<String> output) {
        this.scheduler = scheduler;
        this.mailbox = mailbox;
        this.clockSpeed = clockSpeed;
        this.trace = trace;
        this.output = output;
        this.process = null;
        this.dataBuffer = new ArrayDeque<>();
        this.instructionCache = new HashMap<>();
        this.varCache = new HashMap<>();
        this.labelCache = new HashMap<>();
        this.outputs = new HashMap<>();
    }

    /**
     * Write a message to the output log
     * @param message Message
     */
    private void output(String message) {
        Platform.runLater(() -> this.output.add(message));
    }

    /**
     * Write a message to the execution trace
     * @param message Message
     */
    private void log(String message) {
        Platform.runLater(() -> this.trace.add(message));
    }

    /**
     * Entry point when starting the CPU thread
     */
    @Override
    public void run() {
        while (true) {
            //Remove data for any dropped processes
            {
                boolean done = false;
                while (!done) {
                    Message message = this.mailbox.get(Mailbox.CPU);
                    if (message != null) {
                        String[] command = message.getCommand();
                        if (command[0].equals("drop")) {
                            int pid = Integer.parseInt(command[1]);
                            this.instructionCache.remove(pid);
                            this.varCache.remove(pid);
                            this.labelCache.remove(pid);
                            try {
                                this.outputs.get(pid).close();
                            } catch (Exception ignored) {}
                            this.outputs.remove(pid);
                            this.output("[CPU] Dropped PID " + pid);
                        }
                    }
                    else{
                        done = true;
                    }
                }
            }

            //Get a reference to running process
            this.process = this.scheduler.getRunning();
            if (this.process != null) {
                int pid = this.process.getPid();
                this.dataBuffer.clear();

                //Scan process file for labels, in a real system this would be done at compile time.
                //Compiling the process code is beyond the scope of this simulator, but labels must be loaded here
                //otherwise attempting to jump to a line that hasn't already been executed will fail
                if (!this.labelCache.containsKey(pid)) {
                    this.labelCache.put(pid, new HashMap<>());
                    File file = new File(this.process.getCodePath().toString());
                    try {
                        BufferedReader reader = new BufferedReader(new FileReader(file));
                        for (int i = 0; i < this.process.getCodeLength(); i++) {
                            String[] split = reader.readLine().split(":", 2);
                            if (split.length == 2) {
                                this.labelCache.get(pid).put(split[0], i);
                            }
                        }
                        reader.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                //If there is no instruction cached, try and pull one from the mailbox, otherwise request a new one
                if (!this.instructionCache.containsKey(pid)) {
                    Message message = this.mailbox.get(String.valueOf(pid));
                    if (message == null) {
                        this.mailbox.put(String.valueOf(pid), Mailbox.MMU, "read|" + pid + "|" + this.process.pc + "|true");
                        this.block();
                    }
                    else {
                        this.instructionCache.put(pid, message.getCommand()[1]);
                    }
                }

                //Check that the process wasn't just blocked
                if (this.process != null) {
                    //Load requested data into buffer
                    boolean done = false;
                    while(!done) {
                        Message message = this.mailbox.get(String.valueOf(pid));
                        if (message != null) {
                            String[] command = message.getCommand();
                            if (command[0].equals("data")) {
                                this.dataBuffer.add(command[1]);
                                if (command[2].equals("true")) {
                                    done = true;
                                }
                            }
                        }
                        else {
                            done = true;
                        }
                    }

                    //Split label from instruction
                    String instruction;
                    String[] split = this.instructionCache.get(pid).split(":", 2);
                    try {
                        //Will throw exception if there is no label on this line
                        instruction = split[1];
                    }
                    catch (ArrayIndexOutOfBoundsException e) {
                        instruction = split[0];
                    }

                    //If data was provided execute instruction with it. If not, execution will determine the data needed
                    if (this.dataBuffer.isEmpty()) {
                        this.exec(instruction);
                        this.log("[" + pid + "] " + instruction);
                    }
                    else {
                        this.execData(instruction, this.dataBuffer);
                        this.log("[" + pid + "/DATA] " + instruction);
                    }
                }
            }

            //Wait for next clock cycle
            try {
                Thread.sleep((long) ((1/clockSpeed) * 1000));
            }
            catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Convert an address referenced by a process to the actual virtual address of that data
     * @param address Address as seen by the process
     * @return The corresponding virtual address
     */
    private int getRealAddress(int address) {
        return address + this.process.getCodeLength();
    }

    /**
     * Block the current process
     */
    private void block() {
        this.scheduler.block(this.process);
        this.process = null;
    }

    /**
     * Drop the current process
     */
    private void drop() {
        this.mailbox.put(Mailbox.CPU, Mailbox.SCHEDULER, "drop|" + this.process.getPid());
        this.block();
    }

    /**
     * Go to the next instruction
     */
    private void next() {
        this.instructionCache.remove(this.process.getPid());
        this.process.pc++;
    }

    /**
     * Read a variable from memory
     * @param var Variable
     * @param last True if this is the last in a series of read operations
     */
    private void readVar(String var, boolean last) {
        if (this.varCache.containsKey(this.process.getPid())) {
            if (this.varCache.get(this.process.getPid()).containsKey(var)) {
                if (last) {
                    this.mailbox.put(String.valueOf(this.process.getPid()), Mailbox.MMU, "read|" + this.process.getPid() + "|" + this.varCache.get(this.process.getPid()).get(var) + "|true");
                }
                else {
                    this.mailbox.put(String.valueOf(this.process.getPid()), Mailbox.MMU, "read|" + this.process.getPid() + "|" + this.varCache.get(this.process.getPid()).get(var) + "|false");
                }
            }
            else {
                throw new IllegalArgumentException("Variable not defined");
            }
        }
        else {
            throw new IllegalArgumentException("Variable not defined");
        }
    }

    /**
     * Write to the memory location of a variable
     * @param var Variable
     * @param data Data to write
     * @param last True if this is the last in a series of write operations
     * @param <T> Type of the data being written, will be converted to a String
     */
    private <T> void writeVar(String var, T data, boolean last) {
        if (this.varCache.containsKey(this.process.getPid())) {
            if (this.varCache.get(this.process.getPid()).containsKey(var)) {
                if (last) {
                    this.mailbox.put(Mailbox.CPU, Mailbox.MMU, "write|" + this.process.getPid() + "|" + this.varCache.get(this.process.getPid()).get(var) +  "|" + data + "|true");
                }
                else {
                    this.mailbox.put(Mailbox.CPU, Mailbox.MMU, "write|" + this.process.getPid() + "|" + this.varCache.get(this.process.getPid()).get(var) +  "|" + data + "|false");
                }
            }
            else {
                throw new IllegalArgumentException("Variable not defined");
            }
        }
        else {
            throw new IllegalArgumentException("Variable not defined");
        }
    }

    /**
     * Execute an instruction.<br>
     * This method takes no data, so this is either for instructions that don't need data, or for determining what data is required.
     * @param instruction Instruction to execute
     */
    private void exec(String instruction) {
        try {
            int pid = this.process.getPid();
            String[] tokens = instruction.split("\\s");
            switch (tokens[0]) {
                //Empty line
                case "null": {
                    this.next();
                }
                break;

                //var [name] [address] {value}
                case "var": {
                    //Create a varCache entry for this process
                    if (!this.varCache.containsKey(pid)) {
                        this.varCache.put(pid, new HashMap<>());
                    }
                    this.varCache.get(pid).put(tokens[1], this.getRealAddress(Integer.parseInt(tokens[2])));
                    //Optionally assign a value to the variable
                    if (tokens.length == 4) {
                        this.writeVar(tokens[1], tokens[3], true);
                        this.next();
                        this.block();
                    }
                    else {
                        this.next();
                    }
                }
                break;

                //alloc [blocks]
                case "alloc": {
                    this.mailbox.put(Mailbox.CPU, Mailbox.MMU, "allocate|" + pid + "|" + tokens[1] + "|false");
                    this.next();
                    this.block();
                }
                break;

                //free [blocks]
                case "free": {
                    this.mailbox.put(Mailbox.CPU, Mailbox.MMU, "free|" + pid + "|" + tokens[1] + "|false");
                    this.next();
                }
                break;

                //exit
                case "exit": {
                    this.drop();
                }
                break;

                //jump [label]
                case "jump": {
                    if (this.labelCache.containsKey(pid)) {
                        if (this.labelCache.get(pid).containsKey(tokens[1])) {
                            this.process.pc = this.labelCache.get(pid).get(tokens[1]);
                            this.instructionCache.remove(pid);
                        }
                        else {
                            throw new IllegalArgumentException("Label not defined");
                        }
                    }
                    else {
                        throw new IllegalArgumentException("Label not defined");
                    }
                }
                break;

                //jumpif [var1] [comparator] [var2/value] [label]
                case "jumpif": {
                    if (this.labelCache.containsKey(pid)) {
                        if (this.labelCache.get(pid).containsKey(tokens[4])) {
                            //Check if var2 is value
                            if (this.varCache.get(pid).containsKey(tokens[3])) {
                                this.readVar(tokens[1], false);
                                this.readVar(tokens[3], true);
                            }
                            else {
                                this.readVar(tokens[1], true);
                            }
                            this.block();
                        }
                        else {
                            throw new IllegalArgumentException("Label not defined");
                        }
                    }
                    else {
                        throw new IllegalArgumentException("Label not defined");
                    }
                }
                break;

                //set [var] [var/value]
                case "set": {
                    //Check if setting to a variable
                    if (this.varCache.get(pid).containsKey(tokens[2])) {
                        this.readVar(tokens[2], true);
                    }
                    else {
                        this.writeVar(tokens[1], tokens[2], true);
                        this.next();
                    }
                    this.block();
                }
                break;

                //out [var]
                case "out":
                //inc [var]
                case "inc":
                //dec [var]
                case "dec": {
                    this.readVar(tokens[1], true);
                    this.block();
                }
                break;

                //math [expression]
                case "math": {
                    this.mathVars = new ArrayList<>();

                    //Merge tokens back into one string
                    StringBuilder builder = new StringBuilder();
                    for (int i = 1; i < tokens.length; i++) {
                        builder.append(tokens[i]);
                    }
                    String expression = builder.toString().replaceAll("\\s", "");

                    //Split at brackets and operators
                    String[] split = expression.split("[()+\\-*/%=]");

                    //Find variables, start at index 1 as index 0 will be the variable to output to
                    for (int i = 1; i < split.length; i++) {
                        if (this.varCache.get(pid).containsKey(split[i])) {
                            this.mathVars.add(split[i]);
                        }
                    }

                    //Request data
                    for (int i = 0; i < this.mathVars.size(); i++) {
                        this.readVar(this.mathVars.get(i), i == this.mathVars.size() - 1);
                    }
                    this.block();
                }
                break;

                default: {
                    throw new IllegalArgumentException("Invalid instruction");
                }
            }
        }
        catch (Exception e) {
            //Output exception caused by process and drop it
            e.printStackTrace();
            this.output("[CPU/ERROR] " + e.getClass().getSimpleName() + " in PID " + this.process.getPid() + " at '" + instruction + "': " + e.getMessage());
            this.drop();
        }
    }

    /**
     * Execute an instruction with data.<br>
     * The code to run once an instruction has requested the required data goes here.
     * @param instruction Instruction to execute
     * @param data Data to use in the execution
     */
    private void execData(String instruction, Deque<String> data) {
        try {
            int pid = this.process.getPid();
            String[] tokens = instruction.split("\\s");
            switch (tokens[0]) {
                //out [var]
                case "out": {
                    //Check output dir exists
                    File dir = new File("output");
                    if (!dir.exists()) {
                        Files.createDirectory(dir.toPath());
                    }
                    //Create a new output writer if needed
                    if (!this.outputs.containsKey(pid)) {
                        File file;
                        String[] path = this.process.getCodePath().toString().split("[/\\\\]");
                        String name = path[path.length - 1];
                        if (!Files.exists(Path.of("output", name))) {
                            file = new File("output", name);
                        }
                        else {
                            int count = 1;
                            while (Files.exists(Path.of("output", name.split("\\.")[0] + "(" + count + ").txt"))) {
                                count++;
                            }
                            file = new File("output", name.split("\\.")[0] + "(" + count + ").txt");
                        }
                        this.outputs.put(pid, new BufferedWriter(new FileWriter(file)));
                    }

                    //Output var to console and to file
                    String out = Objects.requireNonNull(data.poll());
                    this.output("[" + pid + "] " + out);
                    this.outputs.get(pid).write(out);
                    this.outputs.get(pid).newLine();
                    this.next();
                }
                break;

                //inc [var]
                case "inc": {
                    this.writeVar(tokens[1], Double.parseDouble(Objects.requireNonNull(data.poll())) + 1, true);
                    this.next();
                    this.block();
                }
                break;

                //dec [var]
                case "dec": {
                    this.writeVar(tokens[1], Double.parseDouble(Objects.requireNonNull(data.poll())) - 1, true);
                    this.next();
                    this.block();
                }
                break;

                //set [var] [var]
                case "set": {
                    this.writeVar(tokens[1], data.poll(), true);
                    this.next();
                    this.block();
                }
                break;

                //jumpif [var1] [comparator] [var2] [label]
                case "jumpif": {
                    //Check if var2 is value
                    String var1;
                    String var2;
                    if (this.varCache.get(pid).containsKey(tokens[3])) {
                        var1 = Objects.requireNonNull(data.poll());
                        var2 = Objects.requireNonNull(data.poll());
                    }
                    else {
                        var1 = Objects.requireNonNull(data.poll());
                        var2 = tokens[3];
                    }
                    //If the variable are numbers, format them as doubles
                    try {
                        var1 = String.valueOf(Double.parseDouble(var1));
                    }
                    catch (Exception ignored) {}
                    try {
                        var2 = String.valueOf(Double.parseDouble(var2));
                    }
                    catch (Exception ignored) {}

                    //Compare
                    switch (tokens[2]) {
                        case "==": {
                            if (var1.equals(var2)) {
                                this.instructionCache.remove(pid);
                                this.process.pc = this.labelCache.get(pid).get(tokens[4]);
                            }
                            else {
                                this.next();
                            }
                        }
                        break;

                        case "!=": {
                            if (!var1.equals(var2)) {
                                this.instructionCache.remove(pid);
                                this.process.pc = this.labelCache.get(pid).get(tokens[4]);
                            }
                            else {
                                this.next();
                            }
                        }
                        break;

                        case ">": {
                            if (Double.parseDouble(var1) > Double.parseDouble(var2)) {
                                this.instructionCache.remove(pid);
                                this.process.pc = this.labelCache.get(pid).get(tokens[4]);
                            }
                            else {
                                this.next();
                            }
                        }
                        break;

                        case "<": {
                            if (Double.parseDouble(var1) < Double.parseDouble(var2)) {
                                this.instructionCache.remove(pid);
                                this.process.pc = this.labelCache.get(pid).get(tokens[4]);
                            }
                            else {
                                this.next();
                            }
                        }
                        break;

                        case ">=": {
                            if (Double.parseDouble(var1) >= Double.parseDouble(var2)) {
                                this.instructionCache.remove(pid);
                                this.process.pc = this.labelCache.get(pid).get(tokens[4]);
                            }
                            else {
                                this.next();
                            }
                        }
                        break;

                        case "<=": {
                            if (Double.parseDouble(var1) <= Double.parseDouble(var2)) {
                                this.instructionCache.remove(pid);
                                this.process.pc = this.labelCache.get(pid).get(tokens[4]);
                            }
                            else {
                                this.next();
                            }
                        }
                        break;

                        default: {
                            throw new IllegalArgumentException("Invalid comparator: " + tokens[2]);
                        }
                    }
                }
                break;

                //math [expression]
                case "math": {
                    //Merge tokens back into one string
                    StringBuilder builder = new StringBuilder();
                    for (int i = 1; i < tokens.length; i++) {
                        builder.append(tokens[i]);
                    }
                    String expression = builder.toString().replaceAll("\\s", "");
                    String target;
                    {
                        String[] split = expression.split("=");
                        target = split[0];
                        expression = split[1];
                    }

                    //Sub in data
                    for (String var : this.mathVars) {
                        expression = expression.replaceAll(var, Objects.requireNonNull(this.dataBuffer.poll()));
                    }

                    //Add brackets to list in order they must be evaluated in - inner brackets followed by outer brackets
                    List<String> operations = new ArrayList<>();
                    if (expression.contains("(")) {
                        boolean done = false;
                        while (!done) {
                            //Find the first closing bracket
                            int close = expression.indexOf(")");
                            int open;
                            boolean found = false;
                            int i = 1;
                            //Go backwards to find the opening bracket
                            while (!found) {
                                if (expression.substring(close - i, (close - i) + 1).equals("(")) {
                                    open = close - i;
                                    //Add contents of brackets to list
                                    operations.add(expression.substring(open + 1, close));
                                    //Replace bracket with b:n where n is the index of the bracket in the list
                                    expression = expression.replace(expression.substring(open, close + 1), "b:"+ (operations.size() - 1));
                                    found = true;
                                }
                                i++;
                            }
                            //If there are no more brackets, break loop
                            if (!expression.contains("(")) {
                                done = true;
                            }
                        }
                    }

                    //Add expression as final operation
                    operations.add(expression);

                    //Evaluate
                    for (int i = 0; i < operations.size(); i++) {
                        //Extract operators
                        Deque<String> operators = new ArrayDeque<>();
                        for (String s : operations.get(i).split("[^+\\-*/%]")) {
                            if (!s.equals("")) {
                                operators.add(s);
                            }
                        }

                        //Split at operators
                        String[] split = operations.get(i).split("[+\\-*/%]");
                        //Sub in previous operations
                        for (int j = 0; j < split.length; j++) {
                            if (split[j].matches("b:[0-9]+")) {
                                split[j] = operations.get(Integer.parseInt(split[j].split(":")[1]));
                            }
                        }

                        //Calculate result
                        double result = Double.parseDouble(split[0]);
                        for (int j = 1; j < split.length; j++) {
                            switch (Objects.requireNonNull(operators.poll())) {
                                case "+":
                                    result = result + Double.parseDouble(split[j]);
                                    break;

                                case "-":
                                    result = result - Double.parseDouble(split[j]);
                                    break;

                                case "*":
                                    result = result * Double.parseDouble(split[j]);
                                    break;

                                case "/":
                                    result = result / Double.parseDouble(split[j]);
                                    break;

                                case "%":
                                    result = result % Double.parseDouble(split[j]);
                                    break;
                            }
                        }
                        operations.set(i, String.valueOf(result));
                    }

                    //Write result to target
                    this.writeVar(target, operations.get(operations.size() - 1), true);
                    this.next();
                    this.block();
                }
                break;
            }
        }
        catch (Exception e) {
            //Output exception caused by process and drop it
            e.printStackTrace();
            this.output("[CPU/ERROR] " + e.getClass().getSimpleName() + " in PID " + this.process.getPid() + " at '" + instruction + "': " + e.getMessage());
            this.drop();
        }
    }
}
