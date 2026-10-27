// Native crash report for the app log: a SIGSEGV/SIGABRT in ncnn or the Vulkan driver kills the
// process before Java can write anything, so the signal handler writes the signal, the faulting
// address, the module + offset of the crashing code, a backtrace and the last native step
// ("stage") to a small text file. The app reads it at the next start and adds it to the log.
#ifndef FLOWFRAMES_CRASH_HANDLER_H
#define FLOWFRAMES_CRASH_HANDLER_H

namespace flowframes {

// Installs the handlers (once) and sets the report file.
void installCrashHandler(const char* reportPath);

// Breadcrumb: a static string naming what native code is doing now (shown in the report).
void setStage(const char* stage);

// Current breadcrumb (for the live job log while an engine builds).
const char* currentStage();

} // namespace flowframes

#endif
