package com.the412banner.batterylog.apps;

// Runs shell scripts inside Shizuku's process (shell or root uid).
interface IShellService {
    String run(String script) = 1;
    // Transaction code Shizuku uses to stop a user service.
    void destroy() = 16777114;
}
