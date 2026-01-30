package com.rundeck.plugin.nodecopy

import groovy.transform.CompileStatic

/**
 * Holds SSH credentials for a node retrieved from Rundeck's configuration.
 */
@CompileStatic
class NodeCredentials {
    final String username
    final int port
    final byte[] privateKey
    final String passphrase

    NodeCredentials(String username, int port, byte[] privateKey, String passphrase) {
        this.username = username
        this.port = port
        this.privateKey = privateKey
        this.passphrase = passphrase
    }
}
