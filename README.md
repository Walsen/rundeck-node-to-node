# Rundeck Node File Copy Plugin

A Rundeck workflow step plugin that enables copying files and directories from one source node to multiple destination nodes using SSH/SFTP. Integrates with Rundeck's node definitions and key storage for seamless authentication.

## Features

- Copy files or directories from one node to multiple destinations (1-to-N)
- Uses Rundeck's node definitions - no manual SSH configuration needed
- Retrieves SSH keys from Rundeck's secure key storage
- Two transfer modes:
  - **Via Rundeck**: Files transfer through Rundeck server (default, most reliable)
  - **Direct**: Source node pushes directly to destinations (faster, requires node-to-node SSH)
- Parallel or sequential transfers to multiple destinations
- Continue on error option for partial success handling
- Recursive directory copying with attribute preservation
- Supports modern SSH algorithms (rsa-sha2-256, rsa-sha2-512)

## Requirements

- Rundeck 5.x or later
- Java 11+
- Nodes must be defined in Rundeck with SSH key storage paths configured

## Installation

1. Download the latest JAR from [Releases](../../releases)

2. Copy to Rundeck's libext directory:

   ```bash
   cp rundeck-node-file-copy-plugin-*.jar $RDECK_BASE/libext/
   ```

3. Restart Rundeck or wait for plugin auto-reload

## Node Configuration

The plugin uses Rundeck's existing node definitions. Each node must have:

| Node Attribute | Description |
| -------------- | ----------- |
| `hostname` | Node hostname or IP (standard Rundeck attribute) |
| `username` | SSH username for the node |
| `ssh-key-storage-path` | Path to SSH private key in Rundeck key storage |
| `ssh-port` | (Optional) SSH port, defaults to 22 |

### Example Node Definition (YAML)

```yaml
app-server-01:
  hostname: 192.168.1.10
  username: deploy
  ssh-key-storage-path: keys/project/myproject/deploy-key
  tags: app,production

backup-server:
  hostname: 192.168.1.50
  username: backup
  ssh-key-storage-path: keys/project/myproject/backup-key
  tags: backup,production
```

### Setting Up Key Storage

1. Go to **Project Settings** → **Key Storage**
2. Add your SSH private keys (PEM/RSA format)
3. Reference the path in node definitions (e.g., `keys/project/myproject/deploy-key`)

## Plugin Properties

| Property | Required | Default | Description |
| -------- | -------- | ------- | ----------- |
| Source Node | Yes | - | Name of source node (as defined in Rundeck) |
| Source Path | Yes | - | Path to file/directory on source |
| Destination Nodes | Yes | - | Comma-separated list of destination node names |
| Destination Path | Yes | - | Target path on all destinations |
| Recursive Copy | No | true | Enable recursive directory copy |
| Preserve Attributes | No | true | Preserve timestamps and permissions |
| Transfer Mode | No | via-rundeck | `via-rundeck` or `direct` |
| Temp Directory | No | /tmp | Temp dir for via-rundeck mode |
| Connection Timeout | No | 30 | SSH timeout in seconds |
| Parallel Transfers | No | true | Transfer to multiple destinations in parallel |
| Continue on Error | No | false | Continue if some destinations fail |

## Transfer Modes

### Via Rundeck Mode (Default)

Files are downloaded once to the Rundeck server, then uploaded to all destinations. Most reliable option.

```
Source Node --[SFTP]--> Rundeck Server --[SFTP]--> Destination Node 1
                                       --[SFTP]--> Destination Node 2
                                       --[SFTP]--> Destination Node N
```

### Direct Mode

Source node pushes directly to each destination using SCP. Faster but requires the source node to have SSH access to all destinations.

```
Source Node --[SCP]--> Destination Node 1
            --[SCP]--> Destination Node 2
            --[SCP]--> Destination Node N
```

## Usage Examples

### Single Destination

```yaml
- configuration:
    sourceNode: app-server-01
    sourcePath: /var/log/myapp
    destinationNodes: backup-server
    destinationPath: /backup/logs/app-server-01
    recursive: 'true'
    transferMode: via-rundeck
  type: node-file-copy
```

### Multiple Destinations

```yaml
- configuration:
    sourceNode: config-master
    sourcePath: /etc/myapp/config.yml
    destinationNodes: app-server-01, app-server-02, app-server-03
    destinationPath: /etc/myapp/
    parallelTransfers: 'true'
    continueOnError: 'true'
  type: node-file-copy
```

### Deploy to All App Servers

```yaml
- defaultTab: nodes
  description: Deploy configuration to all app servers
  name: Deploy Config
  sequence:
    commands:
    - configuration:
        sourceNode: deploy-server
        sourcePath: /releases/current/config
        destinationNodes: app-01, app-02, app-03, app-04
        destinationPath: /opt/myapp/config
        recursive: 'true'
        preserveAttributes: 'true'
        parallelTransfers: 'true'
        continueOnError: 'false'
      type: node-file-copy
```

## Testing

A Docker-based test environment is included in the `test/` directory:

```bash
cd test
./setup.sh      # Build plugin and start test environment
./teardown.sh   # Stop and cleanup
```

This starts:
- Rundeck server with PostgreSQL on http://localhost:8081
- Three test nodes (source-node, dest-node-1, dest-node-2)

See [test/README.md](test/README.md) for detailed testing instructions.

## Troubleshooting

### Node Not Found

- Verify the node name matches exactly what's defined in Rundeck
- Check the node is visible in the project's Nodes tab

### Authentication Failed

- Verify `ssh-key-storage-path` attribute is set on the node
- Check the key exists in Rundeck's key storage
- Ensure the key format is correct (PEM/RSA format)
- The plugin supports modern SSH algorithms (rsa-sha2-256/512)

### Connection Refused

- Verify SSH service is running on target nodes
- Check firewall rules allow SSH traffic
- Confirm `ssh-port` attribute if using non-standard port

### Permission Denied

- Verify the SSH user has read access on source path
- Verify the SSH user has write access on destination path

## Building from Source

```bash
git clone <repository-url>
cd rundeck-node-file-copy-plugin

# Build
./gradlew build

# Run tests
./gradlew test

# The plugin JAR will be in build/libs/
```

## Changelog

### v1.0.1
- Fix: Single file copy with rename now works correctly
- Fix: Auto-create parent directories when destination path doesn't exist

### v1.0.0
- Initial release
- Via-rundeck and direct transfer modes
- Parallel transfers support
- Continue on error option
- Modern SSH algorithm support (mwiede/jsch)

## License

See [LICENSE](LICENSE) file.
