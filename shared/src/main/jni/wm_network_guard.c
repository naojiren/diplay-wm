#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <net/if.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "wm_network_guard_policy.h"

/* Broadcom's published wl_ioctl ABI; constants: platform/hardware/broadcom/wlan wlioctl.h.
 * No channel, association, firmware image, route, credentials or persistent setting is changed. */
struct wl_ioctl { uint32_t cmd; void *buf; uint32_t len; uint8_t set; uint32_t used, needed; };
struct radio_context { int fd, pm_attempted; };
static const char *journal_path = "/data/user/0/com.shihab.diplay.wmex5/files/wm-network-guard.state";
static volatile sig_atomic_t stopping;
static void stop_signal(int sig) { (void)sig; stopping = 1; }
static double monotonic_seconds(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}
static int wl_ioctl_value(int fd, uint32_t command, void *value, uint32_t length, int set) {
    struct { struct wl_ioctl wl; uint32_t driver; } request = {0};
    struct ifreq iface = {0};
    strcpy(iface.ifr_name, "wlan0");
    request.wl.cmd = command;
    request.wl.buf = value;
    request.wl.len = length;
    request.wl.set = (uint8_t)set;
    iface.ifr_data = (void *)&request;
    return ioctl(fd, SIOCDEVPRIVATE, &iface);
}
static int wl_iovar_int(int fd, const char *name, int *value, int set) {
    unsigned char buffer[128] = {0};
    size_t length = strlen(name) + 1;
    if (length + sizeof(*value) > sizeof(buffer)) return -1;
    memcpy(buffer, name, length);
    if (set) memcpy(buffer + length, value, sizeof(*value));
    int result = wl_ioctl_value(fd, set ? 263 : 262, buffer, set ? (uint32_t)(length + sizeof(*value)) : sizeof(buffer), set);
    if (!result && !set) memcpy(value, buffer, sizeof(*value));
    return result;
}
static int read_setting(void *ctx, enum wm_setting setting, int *value) {
    int fd = ((struct radio_context *)ctx)->fd;
    return setting == WM_SLEEP_RETURN ? wl_iovar_int(fd, "pm2_sleep_ret", value, 0) :
        wl_ioctl_value(fd, setting == WM_PM ? 85 : 115, value, sizeof(*value), 0);
}
static int write_setting(void *ctx, enum wm_setting setting, int value) {
    struct radio_context *radio = ctx;
    if (setting == WM_PM && value == 0) {
        if (radio->pm_attempted) return 0;
        radio->pm_attempted = 1;
    }
    int result = setting == WM_SLEEP_RETURN ? wl_iovar_int(radio->fd, "pm2_sleep_ret", &value, 1) :
        wl_ioctl_value(radio->fd, setting == WM_PM ? 86 : 116, &value, sizeof(value), 1);
    if (result != 0) printf("WM radio guard setFailed setting=%d errno=%d\n", (int)setting, errno);
    return result;
}
static int supported_driver(int fd) {
    uint32_t magic = 0, revision[32] = {0};
    if (wl_ioctl_value(fd, 0, &magic, sizeof(magic), 0) != 0 ||
        wl_ioctl_value(fd, 98, revision, sizeof(revision), 0) != 0) {
        printf("WM radio guard queryFailed errno=%d uid=%u permissionDenied=%d\n", errno,
            (unsigned)getuid(), errno == EPERM || errno == EACCES);
        return 0;
    }
    if (magic != 0x14e46c77 || revision[11] != 0x4339) {
        printf("WM radio guard driverMismatch magic=%x chip=%x\n", magic, revision[11]);
        return 0;
    }
    return 1;
}
static int valid_lease_path(const char *path) {
    const char *prefix = "/data/user/0/com.shihab.diplay.wmex5/files/wm-radio-lease-";
    size_t n = strlen(prefix);
    return strncmp(path, prefix, n) == 0 && strlen(path + n) == 32 &&
        strspn(path + n, "0123456789abcdef") == 32;
}
/* Read only a small app-owned heartbeat. Missing lease means immediate release. */
static int read_lease(const char *path, char *value, size_t capacity) {
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? -1 : 0;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) { close(fd); return -1; }
    ssize_t size = read(fd, value, capacity - 1);
    close(fd);
    if (size <= 0) return 0;
    value[size] = 0;
    return 1;
}
/* Detach before the ADB shell exits. Parent waits for actual driver initialization. */
static int detach_lease(const char *path, int *ready_fd) {
    char log_path[256];
    if (snprintf(log_path, sizeof(log_path), "%s.log", path) >= (int)sizeof(log_path)) return -1;
    int pipe_fd[2];
    if (pipe(pipe_fd) != 0) return -1;
    pid_t child = fork();
    if (child < 0) { close(pipe_fd[0]); close(pipe_fd[1]); return -1; }
    if (child > 0) {
        close(pipe_fd[1]);
        struct pollfd event = {pipe_fd[0], POLLIN | POLLHUP, 0};
        char ready = 0;
        if (poll(&event, 1, 5000) > 0) (void)read(pipe_fd[0], &ready, 1);
        close(pipe_fd[0]);
        printf("WM radio guard launched=%d pid=%ld\n", ready == '1', (long)child);
        return ready == '1' ? 1 : -1;
    }
    close(pipe_fd[0]);
    if (setsid() < 0) _exit(10);
    int log_fd = open(log_path, O_WRONLY | O_APPEND | O_NOFOLLOW | O_CLOEXEC);
    int null_fd = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (log_fd < 0 || null_fd < 0) _exit(10);
    if (dup2(null_fd, STDIN_FILENO) < 0 || dup2(log_fd, STDOUT_FILENO) < 0 || dup2(log_fd, STDERR_FILENO) < 0) _exit(10);
    close(log_fd); close(null_fd);
    *ready_fd = pipe_fd[1];
    return 0;
}
static int boot_id(char *out, size_t length) {
    FILE *file = fopen("/proc/sys/kernel/random/boot_id", "r");
    if (!file) return 0;
    int ok = fgets(out, (int)length, file) != NULL;
    fclose(file);
    if (ok) out[strcspn(out, "\r\n")] = 0;
    return ok && strlen(out) == 36;
}
static int owner_alive(long pid) {
    if (pid <= 1) return 0;
    char path[64], command[128] = {0};
    snprintf(path, sizeof(path), "/proc/%ld/cmdline", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t length = read(fd, command, sizeof(command) - 1);
    close(fd);
    return length > 0 && strcmp(command, "com.shihab.diplay.wmex5") == 0;
}
static int save_journal(int fd, const char *boot, struct wm_profile profile) {
    char text[128];
    int length = snprintf(text, sizeof(text), "WM4339v2 %s %d %d %d\n", boot, profile.pm, profile.scan, profile.sleep_return);
    return ftruncate(fd, 0) == 0 && lseek(fd, 0, SEEK_SET) >= 0 &&
        write(fd, text, (size_t)length) == length && fsync(fd) == 0;
}
static int recover_journal(int journal, struct radio_context *radio, const char *boot) {
    char text[128] = {0}, previous_boot[64];
    if (lseek(journal, 0, SEEK_SET) < 0) return 0;
    ssize_t length = read(journal, text, sizeof(text) - 1);
    if (length < 0) return 0;
    if (length == 0) return 1;
    struct wm_profile previous;
    if (sscanf(text, "WM4339v2 %63s %d %d %d", previous_boot, &previous.pm, &previous.scan, &previous.sleep_return) != 4 || !wm_valid(previous)) {
        puts("WM radio guard journal invalid; no changes");
        return 0;
    }
    if (strcmp(boot, previous_boot) == 0 && !wm_restore(radio, read_setting, write_setting, previous)) {
        puts("WM radio guard pending recovery failed");
        return 0;
    }
    return ftruncate(journal, 0) == 0 && fsync(journal) == 0;
}
int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN); // A dead app must not interrupt restoration by closing the log pipe.
    setvbuf(stdout, NULL, _IOLBF, 0);
    int probe = argc == 2 && strcmp(argv[1], "--probe") == 0;
    int recover = argc == 2 && strcmp(argv[1], "--recover") == 0;
    int hold = argc == 3 && strcmp(argv[1], "--hold") == 0;
    int launch = argc == 4 && strcmp(argv[1], "--launch") == 0 && valid_lease_path(argv[3]);
    if (!probe && !recover && !hold && !launch) return 2;
    char *end = NULL;
    long owner = hold || launch ? strtol(argv[2], &end, 10) : 0;
    if ((hold || launch) && (!end || *end || !owner_alive(owner))) return 2;
    char last_lease[64] = {0};
    int ready_fd = -1;
    if (launch) {
        if (getuid() != 0 || read_lease(argv[3], last_lease, sizeof(last_lease)) != 1) return 5;
        int detached = detach_lease(argv[3], &ready_fd);
        if (detached != 0) return detached == 1 ? 0 : 10;
    }
    int radio = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (radio < 0 || !supported_driver(radio)) { puts("WM radio guard unavailable; inspect query/permission diagnostics"); if (radio >= 0) close(radio); return 3; }
    struct wm_profile before;
    struct radio_context radio_ctx = {radio, 0};
    if (!wm_snapshot(&radio_ctx, read_setting, &before)) { close(radio); return 4; }
    printf("WM radio guard probe chip=4339 pm=%d scanSuppress=%d sleepReturnMs=%d uid=%u\n", before.pm, before.scan, before.sleep_return, (unsigned)getuid());
    int pm2_return = -1, dtim = -1;
    if (wl_iovar_int(radio, "pm2_sleep_ret", &pm2_return, 0) == 0) printf("WM radio guard pm2SleepReturnMs=%d\n", pm2_return);
    if (wl_iovar_int(radio, "bcn_li_dtim", &dtim, 0) == 0) printf("WM radio guard dtimListen=%d\n", dtim);
    if (probe) { close(radio); return 0; }
    if (getuid() != 0) { puts("WM radio guard requires existing root; public Wi-Fi lock remains active"); close(radio); return 5; }
    int journal = open(journal_path, O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (journal < 0 || flock(journal, LOCK_EX | LOCK_NB) != 0) {
        puts("WM radio guard journal busy or unavailable"); if (journal >= 0) close(journal); close(radio); return 6;
    }
    char boot[64];
    if (!boot_id(boot, sizeof(boot)) || !recover_journal(journal, &radio_ctx, boot)) { close(journal); close(radio); return 7; }
    if (recover) { puts("WM radio guard recovery complete"); close(journal); close(radio); return 0; }
    if (!wm_snapshot(&radio_ctx, read_setting, &before) || !save_journal(journal, boot, before)) { close(journal); close(radio); return 8; }
    struct sigaction action = {0};
    action.sa_handler = stop_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    int active = wm_enforce(&radio_ctx, read_setting, write_setting);
    struct wm_profile observed;
    if (wm_snapshot(&radio_ctx, read_setting, &observed)) printf("WM radio guard observedPm=%d observedScanSuppress=%d observedSleepReturnMs=%d\n", observed.pm, observed.scan, observed.sleep_return);
    printf("WM radio guard active=%d originalPm=%d originalScanSuppress=%d originalSleepReturnMs=%d\n", active, before.pm, before.scan, before.sleep_return);
    if (ready_fd >= 0) { (void)write(ready_fd, active ? "1" : "0", 1); close(ready_fd); }
    double heartbeat = monotonic_seconds(), checked = heartbeat;
    while (active && !stopping && owner_alive(owner) && monotonic_seconds() - heartbeat < 20) {
        struct pollfd input = {STDIN_FILENO, POLLIN | POLLHUP, 0};
        int ready = launch ? poll(NULL, 0, 1000) : poll(&input, 1, 1000);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) break;
        if (ready > 0 && input.revents) {
            char bytes[64];
            ssize_t count = read(STDIN_FILENO, bytes, sizeof(bytes));
            if (count <= 0) break;
            heartbeat = monotonic_seconds();
        }
        if (launch) {
            char lease[64];
            int present = read_lease(argv[3], lease, sizeof(lease));
            if (present < 0) break;
            if (present > 0 && strcmp(last_lease, lease) != 0) {
                strcpy(last_lease, lease);
                heartbeat = monotonic_seconds();
            }
        }
        double now = monotonic_seconds();
        if (now - checked >= 2) { active = wm_enforce(&radio_ctx, read_setting, write_setting); checked = now; }
    }
    int restored = 0;
    for (int attempt = 0; attempt < 3 && !restored; ++attempt) {
        restored = wm_restore(&radio_ctx, read_setting, write_setting, before);
        if (!restored) { struct timespec delay = {0, 100000000}; nanosleep(&delay, NULL); }
    }
    if (restored && ftruncate(journal, 0) == 0 && fsync(journal) == 0) puts("WM radio guard restored=true");
    else puts("WM radio guard restored=false journal retained for recovery");
    close(journal);
    close(radio);
    return restored ? 0 : 9;
}
