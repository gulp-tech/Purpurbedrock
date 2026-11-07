package com.pexserver.completions;

interface CompletionTask {
    boolean done();
    default void cancel() {}
    void step();
    CompletionModel.Command snapshot();
    CompletionModel.Command result();
}
