#ifndef WM_NETWORK_GUARD_POLICY_H
#define WM_NETWORK_GUARD_POLICY_H

/* Reversible driver settings. Reads/writes are injected for host regression tests. */
enum wm_setting { WM_PM, WM_SCAN, WM_SLEEP_RETURN };
struct wm_profile { int pm, scan, sleep_return; };
typedef int (*wm_read_fn)(void *, enum wm_setting, int *);
typedef int (*wm_write_fn)(void *, enum wm_setting, int);

static int wm_valid(struct wm_profile p) { return p.pm >= 0 && p.pm <= 2 && p.scan >= 0 && p.scan <= 1 && p.sleep_return >= 0 && p.sleep_return <= 60000; }
static int wm_snapshot(void *ctx, wm_read_fn read_value, struct wm_profile *out) {
    return read_value(ctx, WM_PM, &out->pm) == 0 && read_value(ctx, WM_SCAN, &out->scan) == 0 &&
        read_value(ctx, WM_SLEEP_RETURN, &out->sleep_return) == 0 && wm_valid(*out);
}
static int wm_enforce(void *ctx, wm_read_fn read_value, wm_write_fn write_value) {
    struct wm_profile current;
    if (!wm_snapshot(ctx, read_value, &current)) return 0;
    /* This OEM can acknowledge PM_OFF while keeping PM_FAST. Verify the PM2 timeout fallback. */
    if (current.pm != 0) (void)write_value(ctx, WM_PM, 0);
    if (current.sleep_return < 2000 && write_value(ctx, WM_SLEEP_RETURN, 2000) != 0) return 0;
    if (current.scan != 1 && write_value(ctx, WM_SCAN, 1) != 0) return 0;
    return wm_snapshot(ctx, read_value, &current) && current.scan == 1 && (current.pm == 0 || current.sleep_return >= 2000);
}
static int wm_restore(void *ctx, wm_read_fn read_value, wm_write_fn write_value, struct wm_profile original) {
    struct wm_profile current;
    if (!wm_valid(original) || !wm_snapshot(ctx, read_value, &current)) return 0;
    int ok = 1;
    /* Only undo values still equal to this lease's requested value. */
    if (current.scan == 1 && original.scan != 1 && write_value(ctx, WM_SCAN, original.scan) != 0) ok = 0;
    if (current.sleep_return == 2000 && original.sleep_return != 2000 && write_value(ctx, WM_SLEEP_RETURN, original.sleep_return) != 0) ok = 0;
    if (current.pm == 0 && original.pm != 0 && write_value(ctx, WM_PM, original.pm) != 0) ok = 0;
    if (!wm_snapshot(ctx, read_value, &current)) return 0;
    if (original.scan != 1 && current.scan == 1) ok = 0;
    if (original.sleep_return != 2000 && current.sleep_return == 2000) ok = 0;
    if (original.pm != 0 && current.pm == 0) ok = 0;
    return ok;
}
#endif
