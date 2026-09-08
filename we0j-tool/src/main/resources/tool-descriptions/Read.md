Read a file from the local filesystem and return it with 1-based line numbers.

Parameters: path (required, absolute or relative to the working directory), offset (1-based start line, default 1), limit (max lines, default 2000, hard cap 5000).

Output is truncated at 2000 lines / 50KB and per-line at 2000 chars; the result tells you the exact offset to continue reading. Directories, unreadable binary files, and PDFs return an instructive error (use Glob to find files). Images are returned as downsampled base64 data URIs.

Always Read a file before Write or Edit on it — the edit-safety chain rejects modifications to files this session has not read, or that changed on disk since the read. Reading is side-effect free.
