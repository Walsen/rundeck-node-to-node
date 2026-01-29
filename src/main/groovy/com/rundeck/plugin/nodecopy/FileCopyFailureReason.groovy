package com.rundeck.plugin.nodecopy

import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason

/**
 * Enumeration of failure reasons for the Node File Copy plugin.
 */
enum FileCopyFailureReason implements FailureReason {
    
    /** Failed to establish SSH connection to source node */
    SOURCE_CONNECTION_FAILED,
    
    /** Failed to establish SSH connection to destination node */
    DESTINATION_CONNECTION_FAILED,
    
    /** Source file or directory not found */
    SOURCE_NOT_FOUND,
    
    /** Permission denied on source node */
    SOURCE_PERMISSION_DENIED,
    
    /** Permission denied on destination node */
    DESTINATION_PERMISSION_DENIED,
    
    /** Failed to create destination directory */
    DESTINATION_CREATE_FAILED,
    
    /** File transfer failed */
    TRANSFER_FAILED,
    
    /** General copy operation failure */
    COPY_FAILED,
    
    /** SSH authentication failed */
    AUTHENTICATION_FAILED,
    
    /** Invalid configuration provided */
    INVALID_CONFIGURATION,
    
    /** Timeout during operation */
    TIMEOUT,
    
    /** Temporary file operation failed */
    TEMP_FILE_FAILED
}
