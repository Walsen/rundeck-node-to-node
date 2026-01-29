package com.rundeck.plugin.nodecopy

import com.dtolabs.rundeck.core.common.INodeEntry
import com.dtolabs.rundeck.core.common.IRundeckProject
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException
import com.dtolabs.rundeck.core.plugins.Plugin
import com.dtolabs.rundeck.core.storage.ResourceMeta
import com.dtolabs.rundeck.plugins.ServiceNameConstants
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty
import com.dtolabs.rundeck.plugins.descriptions.SelectValues
import com.dtolabs.rundeck.plugins.step.PluginStepContext
import com.dtolabs.rundeck.plugins.step.StepPlugin
import org.rundeck.storage.api.Resource

/**
 * Rundeck Workflow Step Plugin for copying files/directories from one node to multiple nodes.
 * Uses Rundeck's node definitions and key storage for authentication.
 */
@Plugin(name = NodeFileCopyPlugin.PROVIDER_NAME, service = ServiceNameConstants.WorkflowStep)
@PluginDescription(title = "Node File Copy", description = "Copy files or directories from one source node to one or more destination nodes using SSH/SCP")
class NodeFileCopyPlugin implements StepPlugin {

    static final String PROVIDER_NAME = "node-file-copy"
    static final String DEFAULT_SSH_PORT = "22"
    static final String DEFAULT_SSH_USER_ATTR = "username"
    static final String DEFAULT_SSH_KEY_PATH_ATTR = "ssh-key-storage-path"

    @PluginProperty(
        title = "Source Node",
        description = "Name of the source node (must be defined in Rundeck)",
        required = true
    )
    String sourceNode

    @PluginProperty(
        title = "Source Path",
        description = "Path to the file or directory on the source node",
        required = true
    )
    String sourcePath

    @PluginProperty(
        title = "Destination Nodes",
        description = "Comma-separated list of destination node names (must be defined in Rundeck)",
        required = true
    )
    String destinationNodes

    @PluginProperty(
        title = "Destination Path",
        description = "Path where the file/directory should be copied on destination nodes",
        required = true
    )
    String destinationPath

    @PluginProperty(
        title = "Recursive Copy",
        description = "Enable recursive copy for directories",
        defaultValue = "true"
    )
    @SelectValues(values = ["true", "false"])
    String recursive

    @PluginProperty(
        title = "Preserve Attributes",
        description = "Preserve file modification times and permissions",
        defaultValue = "true"
    )
    @SelectValues(values = ["true", "false"])
    String preserveAttributes

    @PluginProperty(
        title = "Transfer Mode",
        description = "Method for transferring files between nodes",
        defaultValue = "via-rundeck"
    )
    @SelectValues(values = ["direct", "via-rundeck"])
    String transferMode

    @PluginProperty(
        title = "Temp Directory",
        description = "Temporary directory on Rundeck server for 'via-rundeck' mode",
        defaultValue = "/tmp"
    )
    String tempDirectory

    @PluginProperty(
        title = "Connection Timeout",
        description = "SSH connection timeout in seconds",
        defaultValue = "30"
    )
    String connectionTimeout

    @PluginProperty(
        title = "Parallel Transfers",
        description = "Execute transfers to multiple destinations in parallel",
        defaultValue = "true"
    )
    @SelectValues(values = ["true", "false"])
    String parallelTransfers

    @PluginProperty(
        title = "Continue on Error",
        description = "Continue copying to remaining nodes if one fails",
        defaultValue = "false"
    )
    @SelectValues(values = ["true", "false"])
    String continueOnError

    @Override
    void executeStep(PluginStepContext context, Map<String, Object> configuration) throws StepException {
        // Resolve configuration
        String srcNodeName = resolveConfig(configuration, "sourceNode", sourceNode)
        String srcPath = resolveConfig(configuration, "sourcePath", sourcePath)
        String dstNodesStr = resolveConfig(configuration, "destinationNodes", destinationNodes)
        String dstPath = resolveConfig(configuration, "destinationPath", destinationPath)
        boolean isRecursive = resolveConfig(configuration, "recursive", recursive).toBoolean()
        boolean preserve = resolveConfig(configuration, "preserveAttributes", preserveAttributes).toBoolean()
        String mode = resolveConfig(configuration, "transferMode", transferMode)
        String tempDir = resolveConfig(configuration, "tempDirectory", tempDirectory)
        int timeout = resolveConfig(configuration, "connectionTimeout", connectionTimeout).toInteger()
        boolean parallel = resolveConfig(configuration, "parallelTransfers", parallelTransfers).toBoolean()
        boolean continueOnErr = resolveConfig(configuration, "continueOnError", continueOnError).toBoolean()

        // Parse destination nodes
        List<String> dstNodeNames = dstNodesStr.split(',').collect { it.trim() }.findAll { it }
        
        if (dstNodeNames.isEmpty()) {
            throw new StepException("No destination nodes specified", FileCopyFailureReason.INVALID_CONFIGURATION)
        }

        // Get node entries from Rundeck
        def project = context.frameworkProject
        INodeEntry srcNode = getNodeEntry(project, srcNodeName)
        
        List<INodeEntry> dstNodes = dstNodeNames.collect { nodeName ->
            getNodeEntry(project, nodeName)
        }

        // Get SSH credentials from Rundeck's key storage
        NodeCredentials srcCreds = getNodeCredentials(context, srcNode)
        Map<INodeEntry, NodeCredentials> dstCredsMap = dstNodes.collectEntries { node ->
            [(node): getNodeCredentials(context, node)]
        }

        context.logger.log(2, "Starting file copy from ${srcNodeName}:${srcPath} to ${dstNodeNames.size()} destination(s)")

        def copyService = new FileCopyService(context.logger)
        List<String> failures = Collections.synchronizedList([])
        List<String> successes = Collections.synchronizedList([])

        if (mode == "via-rundeck") {
            // Download once from source, upload to all destinations
            copyViaRundeckMulti(
                copyService, srcNode, srcCreds, srcPath,
                dstNodes, dstCredsMap, dstPath,
                tempDir, isRecursive, preserve, timeout,
                parallel, continueOnErr, failures, successes, context
            )
        } else {
            // Direct mode: source pushes to each destination
            copyDirectMulti(
                copyService, srcNode, srcCreds, srcPath,
                dstNodes, dstCredsMap, dstPath,
                isRecursive, preserve, timeout,
                parallel, continueOnErr, failures, successes, context
            )
        }

        // Report results
        context.logger.log(2, "Transfer complete: ${successes.size()} succeeded, ${failures.size()} failed")
        
        if (!failures.isEmpty()) {
            String failureMsg = "Failed to copy to nodes: ${failures.join(', ')}"
            if (!continueOnErr || successes.isEmpty()) {
                throw new StepException(failureMsg, FileCopyFailureReason.COPY_FAILED)
            }
            context.logger.log(1, "WARNING: ${failureMsg}")
        }
    }

    private void copyViaRundeckMulti(
            FileCopyService copyService,
            INodeEntry srcNode, NodeCredentials srcCreds, String srcPath,
            List<INodeEntry> dstNodes, Map<INodeEntry, NodeCredentials> dstCredsMap, String dstPath,
            String tempDir, boolean recursive, boolean preserve, int timeout,
            boolean parallel, boolean continueOnErr,
            List<String> failures, List<String> successes,
            PluginStepContext context) {
        
        // Download from source once
        File tempPath = null
        try {
            tempPath = copyService.downloadFromSource(
                srcNode.hostname, srcCreds, srcPath, tempDir, recursive, timeout
            )
            context.logger.log(2, "Downloaded files from source node")

            // Upload to all destinations
            def uploadTask = { INodeEntry dstNode ->
                try {
                    NodeCredentials dstCreds = dstCredsMap[dstNode]
                    copyService.uploadToDestination(
                        dstNode.hostname, dstCreds, tempPath, dstPath, recursive, preserve, timeout
                    )
                    successes.add(dstNode.nodename)
                    context.logger.log(2, "Copied to ${dstNode.nodename}")
                } catch (Exception e) {
                    failures.add(dstNode.nodename)
                    context.logger.log(0, "Failed to copy to ${dstNode.nodename}: ${e.message}")
                    if (!continueOnErr) {
                        throw e
                    }
                }
            }

            if (parallel) {
                dstNodes.parallelStream().forEach(uploadTask)
            } else {
                dstNodes.each(uploadTask)
            }

        } finally {
            if (tempPath) {
                copyService.deleteDirectory(tempPath)
                context.logger.log(3, "Cleaned up temp directory")
            }
        }
    }

    private void copyDirectMulti(
            FileCopyService copyService,
            INodeEntry srcNode, NodeCredentials srcCreds, String srcPath,
            List<INodeEntry> dstNodes, Map<INodeEntry, NodeCredentials> dstCredsMap, String dstPath,
            boolean recursive, boolean preserve, int timeout,
            boolean parallel, boolean continueOnErr,
            List<String> failures, List<String> successes,
            PluginStepContext context) {
        
        def copyTask = { INodeEntry dstNode ->
            try {
                NodeCredentials dstCreds = dstCredsMap[dstNode]
                copyService.copyDirect(
                    srcNode.hostname, srcCreds, srcPath,
                    dstNode.hostname, dstCreds, dstPath,
                    recursive, preserve, timeout
                )
                successes.add(dstNode.nodename)
                context.logger.log(2, "Copied to ${dstNode.nodename}")
            } catch (Exception e) {
                failures.add(dstNode.nodename)
                context.logger.log(0, "Failed to copy to ${dstNode.nodename}: ${e.message}")
                if (!continueOnErr) {
                    throw e
                }
            }
        }

        if (parallel) {
            dstNodes.parallelStream().forEach(copyTask)
        } else {
            dstNodes.each(copyTask)
        }
    }

    private INodeEntry getNodeEntry(String projectName, String nodeName) throws StepException {
        // This will be resolved via context.frameworkProject
        throw new StepException(
            "Node '${nodeName}' not found in project",
            FileCopyFailureReason.INVALID_CONFIGURATION
        )
    }

    private INodeEntry getNodeEntry(IRundeckProject project, String nodeName) throws StepException {
        def nodeSet = project.nodeSet
        def node = nodeSet?.getNode(nodeName)
        
        if (!node) {
            throw new StepException(
                "Node '${nodeName}' not found in project",
                FileCopyFailureReason.INVALID_CONFIGURATION
            )
        }
        return node
    }

    private NodeCredentials getNodeCredentials(PluginStepContext context, INodeEntry node) throws StepException {
        String username = node.attributes?.get(DEFAULT_SSH_USER_ATTR) ?: node.username ?: "rundeck"
        int port = (node.attributes?.get("ssh-port") ?: DEFAULT_SSH_PORT).toString().toInteger()
        
        // Get SSH key from Rundeck's key storage
        String keyStoragePath = node.attributes?.get(DEFAULT_SSH_KEY_PATH_ATTR)
        byte[] privateKey = null
        String passphrase = null
        
        if (keyStoragePath) {
            try {
                def storage = context.executionContext.storageTree
                Resource<ResourceMeta> keyResource = storage.getResource(keyStoragePath)
                
                if (keyResource?.contents) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream()
                    keyResource.contents.writeContent(baos)
                    privateKey = baos.toByteArray()
                }
                
                // Check for passphrase in key storage (convention: same path + .password)
                String passphrasePath = keyStoragePath + ".password"
                try {
                    Resource<ResourceMeta> passphraseResource = storage.getResource(passphrasePath)
                    if (passphraseResource?.contents) {
                        ByteArrayOutputStream pbaos = new ByteArrayOutputStream()
                        passphraseResource.contents.writeContent(pbaos)
                        passphrase = pbaos.toString("UTF-8")
                    }
                } catch (Exception ignored) {
                    // Passphrase is optional
                }
            } catch (Exception e) {
                throw new StepException(
                    "Failed to retrieve SSH key for node '${node.nodename}' from path '${keyStoragePath}': ${e.message}",
                    FileCopyFailureReason.AUTHENTICATION_FAILED
                )
            }
        }

        if (!privateKey) {
            throw new StepException(
                "No SSH key configured for node '${node.nodename}'. Set '${DEFAULT_SSH_KEY_PATH_ATTR}' attribute.",
                FileCopyFailureReason.AUTHENTICATION_FAILED
            )
        }

        return new NodeCredentials(username, port, privateKey, passphrase)
    }

    private String resolveConfig(Map<String, Object> config, String key, String defaultValue) {
        def value = config.get(key)
        if (value && !value.toString().empty) {
            return value.toString()
        }
        return defaultValue
    }
}
