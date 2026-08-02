package com.mardous.booming.separation.process.ipc;

import com.mardous.booming.separation.process.ipc.ISourceSeparationExecutionCallback;

interface ISourceSeparationExecutionService {
    String connect(
        String requestJson,
        ISourceSeparationExecutionCallback callback
    );
    String start(
        String requestJson,
        String observerId,
        String clientProcessName,
        ISourceSeparationExecutionCallback callback
    );
    String updateControl(String requestJson);
    String snapshot(String requestJson);
    String diagnostics(String requestJson);
    String recycle(String requestJson);
    String closeRun(String requestJson);
}
