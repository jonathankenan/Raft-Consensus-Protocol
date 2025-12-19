# Raft Consensus & Key-Value Store

A distributed key-value store implementation using the Raft consensus algorithm in Java.

---

## Prerequisites

Make sure you have installed:
1. **Java JDK 17** or newer
2. **Apache Maven** (verify with `mvn -version`)

---

## Build Project

### 1. Clean Previous Build
```bash
mvn clean
```
**Explanation:**
- `mvn` - Runs the Maven build tool
- `clean` - Deletes the `target/` folder containing previous compilation results
- Useful to ensure no old files interfere with the new build

### 2. Compile and Prepare Dependencies
```bash
mvn compile dependency:copy-dependencies
```
**Explanation:**
- `compile` - Compiles all Java source code from `src/main/java/` to `target/classes/`
- `dependency:copy-dependencies` - Copies all required JAR libraries (jsonrpc4j, jackson, etc.) to `target/dependency/`
- Both steps are required for the program to run with all dependencies

> **Alternative:** For a complete build in one command:
> ```bash
> mvn clean package dependency:copy-dependencies
> ```

---

## Running the Server (Raft Node)

### Command Format
```bash
# Linux / macOS / WSL
java -cp "target/classes:target/dependency/*" com.labbrother.Server <HOST> <PORT> [<CONTACT_HOST> <CONTACT_PORT>]

# Windows (PowerShell / CMD)
java -cp "target/classes;target/dependency/*" com.labbrother.Server <HOST> <PORT> [<CONTACT_HOST> <CONTACT_PORT>]
```

**Parameter Explanation:**
| Parameter | Description |
|-----------|-------------|
| `-cp` | Classpath - location of class files and JAR libraries |
| `target/classes` | Location of our compiled code |
| `target/dependency/*` | Location of all JAR libraries (use `:` for Linux, `;` for Windows) |
| `com.labbrother.Server` | Main class to execute |
| `<HOST>` | Hostname for this node (usually `localhost`) |
| `<PORT>` | Port for this node (e.g., `8001`, `8002`, `8003`) |
| `<CONTACT_HOST>` | **(Optional)** Hostname of an existing node in the cluster |
| `<CONTACT_PORT>` | **(Optional)** Port of an existing node in the cluster |

> **Important:** If `CONTACT_HOST` and `CONTACT_PORT` are not provided, the node will consider itself as the first node and immediately become the **Leader**.

### Example: Running a 3-Node Cluster

#### Linux / macOS / WSL (separator `:`)

**Terminal 1 - Leader Node (Port 8001):**
```bash
java -cp "target/classes:target/dependency/*" com.labbrother.Server localhost 8001
```
*First node, no contact node → becomes Leader*

**Terminal 2 - Follower Node (Port 8002):**
```bash
java -cp "target/classes:target/dependency/*" com.labbrother.Server localhost 8002 localhost 8001
```
*Joins the cluster through the node at port 8001*

**Terminal 3 - Follower Node (Port 8003):**
```bash
java -cp "target/classes:target/dependency/*" com.labbrother.Server localhost 8003 localhost 8001
```
*Joins the cluster through the node at port 8001*

#### Windows PowerShell / CMD (separator `;`)

**Terminal 1 - Leader Node (Port 8001):**
```powershell
java -cp "target/classes;target/dependency/*" com.labbrother.Server localhost 8001
```

**Terminal 2 - Follower Node (Port 8002):**
```powershell
java -cp "target/classes;target/dependency/*" com.labbrother.Server localhost 8002 localhost 8001
```

**Terminal 3 - Follower Node (Port 8003):**
```powershell
java -cp "target/classes;target/dependency/*" com.labbrother.Server localhost 8003 localhost 8001
```

---

## Running the Client

### Command Format
```bash
# Linux / macOS / WSL
java -cp "target/classes:target/dependency/*" com.labbrother.Client <HOST> <PORT>

# Windows (PowerShell / CMD)
java -cp "target/classes;target/dependency/*" com.labbrother.Client <HOST> <PORT>
```

**Parameter Explanation:**
| Parameter | Description |
|-----------|-------------|
| `<HOST>` | Hostname of the server to connect to |
| `<PORT>` | Server port (ideally connect to the Leader) |

### Example
```bash
# Linux
java -cp "target/classes:target/dependency/*" com.labbrother.Client localhost 8001

# Windows
java -cp "target/classes;target/dependency/*" com.labbrother.Client localhost 8001
```

---

## Client Commands

Once the Client is connected, type the following commands:

| Command | Example | Description |
|---------|---------|-------------|
| `ping` | `ping` | Check connection, output: `PONG` |
| `set <key> <value>` | `set user1 john` | Store data with a key |
| `get <key>` | `get user1` | Retrieve value from a key |
| `append <key> <text>` | `append user1 _doe` | Append string to the value |
| `strln <key>` | `strln user1` | Get the string length |
| `del <key>` | `del user1` | Delete key and return its value |

---

## Troubleshooting

| Error | Solution |
|-------|----------|
| `Could not find or load main class` | Run from the project root folder. Make sure `mvn compile` succeeded. Check classpath separator (`;` Windows, `:` Linux) |
| `NoClassDefFoundError: com/googlecode/jsonrpc4j/...` | Run `mvn dependency:copy-dependencies` and ensure `target/dependency/` is not empty |
| `Client timeout / null response` | Make sure at least 2 server nodes are running (Raft requires majority) |
| `Follower Node becomes Leader on its own` | Make sure to include the contact node when starting a Follower |

---

## Team Members - LabBrother

| Name | Student ID |
|------|------------|
| Jonatahan Kenan Budianto | 13523139 |
| Mahesa Fadhillah Andre | 13523140 |
| Anas Ghazi Al Gifari | 13523159 |

## Feature Status

| Feature | Status |
|---------|--------|
| Heartbeat | Complete |
| Leader Election | Complete |
| Log Replication | Complete |
| Membership Change | Complete |
| Unit Testing (bonus) | Complete |
| Transaction (bonus) | Complete |
| Log Compaction (bonus) | Not Started |
