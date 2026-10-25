package com.lifedesk.notes;

/** Result of a note save operation. */
public enum SaveResult {
    SAVED,
    /** External change detected; our content saved and the external version preserved as a -conflict file. */
    SAVED_WITH_CONFLICT_COPY,
    /** Content unchanged since the last save; nothing written. */
    UNCHANGED
}
