#include "crash_handler.h"

#include <atomic>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

namespace flowframes {

namespace {

char g_path[512] = {0};
std::atomic<const char*> g_stage{"idle"};
std::atomic<bool> g_installed{false};
std::atomic<int> g_inHandler{0};
const int kSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP};
struct sigaction g_old[32];
char g_altStack[64 * 1024];

void put(int fd, const char* s) {
    if (s) {
        ssize_t r = write(fd, s, strlen(s));
        (void)r;
    }
}

const char* sigName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV (invalid memory access)";
        case SIGABRT: return "SIGABRT (abort)";
        case SIGBUS: return "SIGBUS (bus error)";
        case SIGILL: return "SIGILL (illegal instruction)";
        case SIGFPE: return "SIGFPE (arithmetic)";
        case SIGTRAP: return "SIGTRAP";
        default: return "signal";
    }
}

void putAddr(int fd, const char* prefix, uintptr_t pc) {
    char buf[640];
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (pc && dladdr(reinterpret_cast<void*>(pc), &info) && info.dli_fname) {
        const char* lib = strrchr(info.dli_fname, '/');
        lib = lib ? lib + 1 : info.dli_fname;
        uintptr_t off = pc - reinterpret_cast<uintptr_t>(info.dli_fbase);
        if (info.dli_sname) {
            uintptr_t soff = pc - reinterpret_cast<uintptr_t>(info.dli_saddr);
            snprintf(buf, sizeof(buf), "%s%s +0x%zx (%s+%zu)\n", prefix, lib, (size_t)off, info.dli_sname, (size_t)soff);
        } else {
            snprintf(buf, sizeof(buf), "%s%s +0x%zx\n", prefix, lib, (size_t)off);
        }
    } else {
        snprintf(buf, sizeof(buf), "%s0x%zx\n", prefix, (size_t)pc);
    }
    put(fd, buf);
}

struct Trace {
    int fd;
    int depth;
};

_Unwind_Reason_Code onFrame(struct _Unwind_Context* ctx, void* arg) {
    Trace* t = static_cast<Trace*>(arg);
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (!pc) return _URC_END_OF_STACK;
    char prefix[16];
    snprintf(prefix, sizeof(prefix), "  #%02d ", t->depth);
    putAddr(t->fd, prefix, pc);
    return ++t->depth >= 40 ? _URC_END_OF_STACK : _URC_NO_REASON;
}

void handler(int sig, siginfo_t* info, void* uctx) {
    if (g_inHandler.fetch_add(1) == 0 && g_path[0]) {
        int fd = open(g_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (fd >= 0) {
            char buf[512];
            char thread[17] = {0};
            prctl(PR_GET_NAME, thread, 0, 0, 0);
            snprintf(buf, sizeof(buf),
                     "Native crash: %s · code %d · fault address %p\nThread: %s (tid %ld)\nNative stage: %s\n",
                     sigName(sig), info ? info->si_code : 0, info ? info->si_addr : nullptr, thread,
                     (long)syscall(SYS_gettid), g_stage.load());
            put(fd, buf);
#if defined(__aarch64__)
            if (uctx) {
                const ucontext_t* uc = static_cast<const ucontext_t*>(uctx);
                putAddr(fd, "pc ", (uintptr_t)uc->uc_mcontext.pc);
                putAddr(fd, "lr ", (uintptr_t)uc->uc_mcontext.regs[30]);
            }
#else
            (void)uctx;
#endif
            put(fd, "Backtrace:\n");
            Trace t{fd, 0};
            _Unwind_Backtrace(onFrame, &t);
            fsync(fd);
            close(fd);
        }
    }
    // Hand over to the previous handler (debuggerd: tombstone, then the process dies).
    for (size_t i = 0; i < sizeof(kSignals) / sizeof(kSignals[0]); i++) {
        if (kSignals[i] == sig) sigaction(sig, &g_old[sig], nullptr);
    }
    if (!info || info->si_code <= 0 || sig == SIGABRT) {
        syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), sig); // sent signal: send it again
    }
    // Faults return and fault again under the previous handler.
}

} // namespace

void installCrashHandler(const char* reportPath) {
    if (reportPath) {
        strncpy(g_path, reportPath, sizeof(g_path) - 1);
        g_path[sizeof(g_path) - 1] = 0;
    }
    if (g_installed.exchange(true)) return;
    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp = g_altStack;
    ss.ss_size = sizeof(g_altStack);
    sigaltstack(&ss, nullptr); // this thread only; other threads use their own stack
    for (int sig : kSignals) {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = handler;
        sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
        sigemptyset(&sa.sa_mask);
        sigaction(sig, &sa, &g_old[sig]);
    }
}

void setStage(const char* stage) {
    g_stage.store(stage ? stage : "?");
}

} // namespace flowframes
