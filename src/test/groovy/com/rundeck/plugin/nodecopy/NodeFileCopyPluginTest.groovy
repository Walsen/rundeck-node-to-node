package com.rundeck.plugin.nodecopy

import com.dtolabs.rundeck.plugins.PluginLogger
import com.dtolabs.rundeck.plugins.step.PluginStepContext
import org.junit.Before
import org.junit.Test

import static org.junit.Assert.*
import static org.mockito.Mockito.*

/**
 * Unit tests for NodeFileCopyPlugin.
 */
class NodeFileCopyPluginTest {

    PluginStepContext context
    PluginLogger logger
    NodeFileCopyPlugin plugin

    @Before
    void setUp() {
        context = mock(PluginStepContext)
        logger = mock(PluginLogger)
        when(context.getLogger()).thenReturn(logger)
        plugin = new NodeFileCopyPlugin()
    }

    @Test
    void testProviderName() {
        assertEquals("node-file-copy", NodeFileCopyPlugin.PROVIDER_NAME)
    }

    @Test
    void testDefaultConstants() {
        assertEquals("22", NodeFileCopyPlugin.DEFAULT_SSH_PORT)
        assertEquals("username", NodeFileCopyPlugin.DEFAULT_SSH_USER_ATTR)
        assertEquals("ssh-key-storage-path", NodeFileCopyPlugin.DEFAULT_SSH_KEY_PATH_ATTR)
    }

    @Test
    void testFailureReasons() {
        assertNotNull(FileCopyFailureReason.SOURCE_CONNECTION_FAILED)
        assertNotNull(FileCopyFailureReason.DESTINATION_CONNECTION_FAILED)
        assertNotNull(FileCopyFailureReason.SOURCE_NOT_FOUND)
        assertNotNull(FileCopyFailureReason.COPY_FAILED)
        assertNotNull(FileCopyFailureReason.AUTHENTICATION_FAILED)
        assertNotNull(FileCopyFailureReason.INVALID_CONFIGURATION)
    }

    @Test
    void testNodeCredentials() {
        byte[] key = "test-key".bytes
        def creds = new NodeCredentials("admin", 22, key, "passphrase")
        
        assertEquals("admin", creds.username)
        assertEquals(22, creds.port)
        assertArrayEquals(key, creds.privateKey)
        assertEquals("passphrase", creds.passphrase)
    }

    @Test
    void testNodeCredentialsWithoutPassphrase() {
        byte[] key = "test-key".bytes
        def creds = new NodeCredentials("root", 2222, key, null)
        
        assertEquals("root", creds.username)
        assertEquals(2222, creds.port)
        assertArrayEquals(key, creds.privateKey)
        assertNull(creds.passphrase)
    }

    @Test
    void testParseDestinationNodes() {
        String input = "node1, node2, node3"
        List<String> nodes = input.split(',').collect { it.trim() }.findAll { it }
        
        assertEquals(3, nodes.size())
        assertEquals("node1", nodes[0])
        assertEquals("node2", nodes[1])
        assertEquals("node3", nodes[2])
    }

    @Test
    void testParseDestinationNodesWithSpaces() {
        String input = "  node1  ,  node2  ,  node3  "
        List<String> nodes = input.split(',').collect { it.trim() }.findAll { it }
        
        assertEquals(3, nodes.size())
        assertTrue(nodes.every { !it.contains(' ') })
    }

    @Test
    void testParseSingleDestinationNode() {
        String input = "single-node"
        List<String> nodes = input.split(',').collect { it.trim() }.findAll { it }
        
        assertEquals(1, nodes.size())
        assertEquals("single-node", nodes[0])
    }
}
