Write a file to the local filesystem, creating it (with parent directories) or overwriting it completely.

Parameters: path (required), content (required, the FULL new file text; pass an empty string to create an empty file).

Prefer Edit for modifying existing files — Write replaces the entire content. If the file already exists you must have Read it in this session first, otherwise this tool rejects the write and asks you to re-read.

The write is atomic (a partial file is never observable on disk). A user permission prompt is shown before writing. Returns the created/updated path and line count.
