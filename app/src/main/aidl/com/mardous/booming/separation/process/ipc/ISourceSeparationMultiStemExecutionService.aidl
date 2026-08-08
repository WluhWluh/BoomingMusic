package com.mardous.booming.separation.process.ipc;

import com.mardous.booming.separation.process.ipc.ISourceSeparationMultiStemExecutionCallback;

interface ISourceSeparationMultiStemExecutionService {
    String connect();
    String start(
        String descriptorJson,
        ISourceSeparationMultiStemExecutionCallback callback
    );
    String updateControl(String commandJson);
    String activeRun();
    String adopt(ISourceSeparationMultiStemExecutionCallback callback);
    void terminateForValidation();
}
