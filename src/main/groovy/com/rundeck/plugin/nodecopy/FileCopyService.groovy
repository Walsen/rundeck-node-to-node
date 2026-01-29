package com.rundeck.plugin.nodecopy

import com.dtolabs.rundeck.plugins.PluginLogger
import com.jcraft.jsch.*
import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Service class handling SSH/SCP file copy operations between nodes.
 * Uses credentials from Rundeck's key storage.
 */
@CompileStatic
class FileCopyService {

    private final PluginLogger logger

    FileCopyService(PluginLogger logger) {
        this.logger = logger
    }

    /**
     * Copy files directly from source to destination using SCP.
     * Executes scp command on source node to push to destination.
     */
    void copyDirect(String srcHost, NodeCredentials srcCreds, String srcPath,
                    String dstHost, NodeCredentials dstCreds, String dstPath,
                    boolean recursive, boolean preserve, int timeout) throws Exception {
        
        logger.log(3, "Using direct copy mode (source -> destination)")
        int timeoutMs = timeout * 1000
        
        Session srcSession = null
        try {
            srcSession = createSession(srcHost, srcCreds, timeoutMs)
            srcSession.connect(timeoutMs)
            logger.log(3, "Connected to source node: ${srcHost}")

            // Build scp command
            def scpCmd = new StringBuilder("scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null")
            if (recursive) {
                scpCmd.append(" -r")
            }
            if (preserve) {
                scpCmd.append(" -p")
            }
            scpCmd.append(" -P ${dstCreds.port}")
            scpCmd.append(" ${srcPath}")
            scpCmd.append(" ${dstCreds.username}@${dstHost}:${dstPath}")

            String command = scpCmd.toString()
            logger.log(3, "Executing: ${command}")

            executeCommand(srcSession, command, timeoutMs)
            logger.log(2, "Direct copy completed successfully")

        } finally {
            srcSession?.disconnect()
        }
    }

    /**
     * Download files from source node to local temp directory.
     */
    File downloadFromSource(String srcHost, NodeCredentials srcCreds, String srcPath,
                            String tempDir, boolean recursive, int timeout) throws Exception {
        
        logger.log(3, "Downloading from source node")
        int timeoutMs = timeout * 1000
        
        Path tempPath = Files.createTempDirectory(Paths.get(tempDir), "node-copy-")
        Session srcSession = null

        try {
            srcSession = createSession(srcHost, srcCreds, timeoutMs)
            srcSession.connect(timeoutMs)
            logger.log(3, "Connected to source node: ${srcHost}")

            downloadFiles(srcSession, srcPath, tempPath.toString(), recursive, timeoutMs)
            return tempPath.toFile()

        } catch (Exception e) {
            // Cleanup on failure
            deleteDirectory(tempPath.toFile())
            throw e
        } finally {
            srcSession?.disconnect()
        }
    }

    /**
     * Upload files from local directory to destination node.
     */
    void uploadToDestination(String dstHost, NodeCredentials dstCreds, File localPath,
                             String dstPath, boolean recursive, boolean preserve, int timeout) throws Exception {
        
        logger.log(3, "Uploading to destination node: ${dstHost}")
        int timeoutMs = timeout * 1000
        
        Session dstSession = null
        try {
            dstSession = createSession(dstHost, dstCreds, timeoutMs)
            dstSession.connect(timeoutMs)

            uploadFiles(dstSession, localPath.absolutePath, dstPath, recursive, preserve, timeoutMs)

        } finally {
            dstSession?.disconnect()
        }
    }

    private Session createSession(String host, NodeCredentials creds, int timeoutMs) throws JSchException {
        JSch jsch = new JSch()
        
        // Add identity from byte array (from Rundeck key storage)
        if (creds.privateKey) {
            if (creds.passphrase) {
                jsch.addIdentity("key", creds.privateKey, null, creds.passphrase.bytes)
            } else {
                jsch.addIdentity("key", creds.privateKey, null, null)
            }
        }

        Session session = jsch.getSession(creds.username, host, creds.port)
        
        Properties config = new Properties()
        config.put("StrictHostKeyChecking", "no")
        config.put("PreferredAuthentications", "publickey,keyboard-interactive,password")
        session.setConfig(config)

        return session
    }

    private void executeCommand(Session session, String command, int timeoutMs) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec")
        channel.setCommand(command)
        channel.inputStream = null
        
        def errStream = new ByteArrayOutputStream()
        channel.errStream = errStream
        
        InputStream input = channel.inputStream
        channel.connect(timeoutMs)

        // Wait for completion
        byte[] tmp = new byte[1024]
        while (true) {
            while (input.available() > 0) {
                int i = input.read(tmp, 0, 1024)
                if (i < 0) break
                logger.log(3, new String(tmp, 0, i))
            }
            if (channel.closed) {
                if (input.available() > 0) continue
                break
            }
            Thread.sleep(100)
        }

        int exitStatus = channel.exitStatus
        channel.disconnect()

        if (exitStatus != 0) {
            String error = errStream.toString()
            throw new Exception("Command failed with exit code ${exitStatus}: ${error}")
        }
    }

    private void downloadFiles(Session session, String remotePath, String localPath, 
                               boolean recursive, int timeoutMs) throws Exception {
        
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp")
        sftp.connect(timeoutMs)

        try {
            SftpATTRS attrs = sftp.stat(remotePath)
            
            if (attrs.dir) {
                if (!recursive) {
                    throw new Exception("Source is a directory but recursive mode is disabled")
                }
                downloadDirectory(sftp, remotePath, localPath)
            } else {
                String fileName = Paths.get(remotePath).fileName.toString()
                String localFile = Paths.get(localPath, fileName).toString()
                sftp.get(remotePath, localFile)
            }
        } finally {
            sftp.disconnect()
        }
    }

    private void downloadDirectory(ChannelSftp sftp, String remotePath, String localPath) throws Exception {
        File localDir = new File(localPath)
        if (!localDir.exists()) {
            localDir.mkdirs()
        }

        Vector<ChannelSftp.LsEntry> entries = sftp.ls(remotePath) as Vector<ChannelSftp.LsEntry>
        
        entries.each { entry ->
            String name = entry.filename
            if (name == "." || name == "..") {
                return
            }

            String remoteFile = "${remotePath}/${name}"
            String localFile = "${localPath}${File.separator}${name}"

            if (entry.attrs.dir) {
                downloadDirectory(sftp, remoteFile, localFile)
            } else {
                sftp.get(remoteFile, localFile)
                logger.log(4, "Downloaded: ${remoteFile}")
            }
        }
    }

    private void uploadFiles(Session session, String localPath, String remotePath, 
                            boolean recursive, boolean preserve, int timeoutMs) throws Exception {
        
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp")
        sftp.connect(timeoutMs)

        try {
            File localFile = new File(localPath)
            
            // Ensure remote directory exists
            Path parentPath = Paths.get(remotePath).parent
            if (parentPath) {
                createRemoteDirectories(sftp, parentPath.toString())
            }

            if (localFile.directory) {
                localFile.listFiles()?.each { file ->
                    uploadPath(sftp, file, remotePath, preserve)
                }
            } else {
                uploadPath(sftp, localFile, remotePath, preserve)
            }
        } finally {
            sftp.disconnect()
        }
    }

    private void uploadPath(ChannelSftp sftp, File localFile, String remotePath, boolean preserve) 
            throws Exception {
        
        String remoteFile = "${remotePath}/${localFile.name}"

        if (localFile.directory) {
            try {
                sftp.mkdir(remoteFile)
            } catch (SftpException e) {
                if (e.id != ChannelSftp.SSH_FX_FAILURE) {
                    throw e
                }
            }

            localFile.listFiles()?.each { file ->
                uploadPath(sftp, file, remoteFile, preserve)
            }
        } else {
            sftp.put(localFile.absolutePath, remoteFile)
            logger.log(4, "Uploaded: ${remoteFile}")

            if (preserve) {
                int mtime = (int) (localFile.lastModified() / 1000)
                sftp.setMtime(remoteFile, mtime)
            }
        }
    }

    private void createRemoteDirectories(ChannelSftp sftp, String path) {
        if (!path) return

        String[] parts = path.split("/")
        StringBuilder current = new StringBuilder()
        
        parts.each { part ->
            if (!part) {
                current.append("/")
                return
            }
            current.append(part).append("/")
            try {
                sftp.stat(current.toString())
            } catch (SftpException e) {
                try {
                    sftp.mkdir(current.toString())
                } catch (SftpException ignored) {}
            }
        }
    }

    void deleteDirectory(File dir) {
        if (!dir?.exists()) return
        
        dir.listFiles()?.each { file ->
            if (file.directory) {
                deleteDirectory(file)
            } else {
                file.delete()
            }
        }
        dir.delete()
    }
}
