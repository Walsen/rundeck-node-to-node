package com.rundeck.plugin.nodecopy

import com.dtolabs.rundeck.plugins.PluginLogger
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

import static org.junit.Assert.*
import static org.mockito.Mockito.*

/**
 * Unit tests for FileCopyService.
 */
class FileCopyServiceTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder()

    PluginLogger logger
    FileCopyService service

    @Before
    void setUp() {
        logger = mock(PluginLogger)
        service = new FileCopyService(logger)
    }

    @Test
    void testDeleteEmptyDirectory() {
        File dir = tempFolder.newFolder("empty-dir")
        assertTrue(dir.exists())
        
        service.deleteDirectory(dir)
        
        assertFalse(dir.exists())
    }

    @Test
    void testDeleteDirectoryWithFiles() {
        File dir = tempFolder.newFolder("test-dir")
        File file1 = new File(dir, "file1.txt")
        File file2 = new File(dir, "file2.txt")
        file1.text = "content1"
        file2.text = "content2"
        
        assertTrue(dir.exists())
        assertTrue(file1.exists())
        assertTrue(file2.exists())
        
        service.deleteDirectory(dir)
        
        assertFalse(dir.exists())
        assertFalse(file1.exists())
        assertFalse(file2.exists())
    }

    @Test
    void testDeleteDirectoryWithNestedStructure() {
        File dir = tempFolder.newFolder("parent")
        File subDir = new File(dir, "child")
        subDir.mkdir()
        File file = new File(subDir, "nested.txt")
        file.text = "nested content"
        
        assertTrue(dir.exists())
        assertTrue(subDir.exists())
        assertTrue(file.exists())
        
        service.deleteDirectory(dir)
        
        assertFalse(dir.exists())
    }

    @Test
    void testDeleteNullDirectory() {
        // Should not throw exception
        service.deleteDirectory(null)
    }

    @Test
    void testDeleteNonExistentDirectory() {
        File dir = new File(tempFolder.root, "non-existent")
        assertFalse(dir.exists())
        
        // Should not throw exception
        service.deleteDirectory(dir)
    }

    @Test
    void testNodeCredentialsCreation() {
        byte[] key = "-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----".bytes
        NodeCredentials creds = new NodeCredentials("testuser", 22, key, null)
        
        assertEquals("testuser", creds.username)
        assertEquals(22, creds.port)
        assertNotNull(creds.privateKey)
        assertNull(creds.passphrase)
    }

    @Test
    void testNodeCredentialsWithCustomPort() {
        byte[] key = "key".bytes
        NodeCredentials creds = new NodeCredentials("admin", 2222, key, "secret")
        
        assertEquals(2222, creds.port)
        assertEquals("secret", creds.passphrase)
    }

    @Test
    void testServiceCreation() {
        assertNotNull(service)
    }

    @Test
    void testLoggerIsCalled() {
        File dir = tempFolder.newFolder("log-test")
        File subDir = new File(dir, "sub")
        subDir.mkdir()
        
        service.deleteDirectory(dir)
        
        // Logger should not be called for deleteDirectory (no logging there)
        // This test just verifies no exceptions occur
        assertFalse(dir.exists())
    }
}
