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

    // BuildConfig.BUILD_TIME of the code this service is running.
    long buildTime() = 5;

    // Disconnects the Joy-Cons and recreates the virtual controller, so that when
    // the Joy-Cons reconnect (a button press on each) it is listed before them.
    // Blocks for up to a few seconds. Returns false if merging isn't running.
    boolean reconnectJoyCons() = 6;
}
