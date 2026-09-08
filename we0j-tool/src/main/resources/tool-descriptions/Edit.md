Perform exact string replacement in a file.

Parameters: path (required), oldText (required, the exact text to replace), newText (required, may be empty to delete the matched block), replaceAll (optional, default false).

Constraints: you must have Read the file in this session; the edit is rejected if the file changed on disk since. oldText must match exactly one location unless replaceAll=true — ambiguity is reported with all matching line numbers. An empty oldText is only valid when the file is currently empty (creates its content).

If your exact oldText does not match, the tool falls back through whitespace-, indentation-, escaping- and block-similarity strategies, preserving the file's original style; do not re-read just to retry with different whitespace. A no-match error means your copy is stale: re-read and copy oldText verbatim.

A diff of the change is shown to the user for approval before the write is applied.
