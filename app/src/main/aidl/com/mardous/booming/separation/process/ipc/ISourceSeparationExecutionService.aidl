package com.mardous.booming.separation.process.ipc;

import com.mardous.booming.separation.process.ipc.ISourceSeparationExecutionCallback;

interface ISourceSeparationExecutionService {
    String connect(
        String requestJson,
        ISourceSeparationExecutionCallback callback
    );
    String start(String requestJson);
    String updateControl(String requestJson);
    String snapshot(String requestJson);
    String diagnostics(String requestJson);
    String closeRun(String requestJson);
}
