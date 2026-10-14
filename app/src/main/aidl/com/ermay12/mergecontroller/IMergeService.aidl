package com.ermay12.mergecontroller;

// Implemented by MergeUserService, which Shizuku runs as the shell user.
interface IMergeService {
    // Called by Shizuku when the service is removed.
    void destroy() = 16777114;

    // Returns 0 or a negative errno; see status() for details.
    int start(int flags) = 1;

    void stop() = 2;

    void setFlags(int flags) = 3;

    // JSON: {running, flags, virtualPath, left, right, error}
    String status() = 4;
}
