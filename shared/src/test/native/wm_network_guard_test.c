#include <assert.h>
#include <stdio.h>
#include "../../main/jni/wm_network_guard_policy.h"
struct fake { int pm, scan, sleep_return, failed_read, failed_write, ignore_pm; };
static int read_fake(void *ctx, enum wm_setting setting, int *out) {
    struct fake *f = ctx;
    if (f->failed_read) return -1;
    *out = setting == WM_PM ? f->pm : (setting == WM_SCAN ? f->scan : f->sleep_return);
    return 0;
}
static int write_fake(void *ctx, enum wm_setting setting, int value) {
    struct fake *f = ctx;
    if (f->failed_write == (int)setting + 1) return -1;
    if (setting == WM_PM) { if (!f->ignore_pm) f->pm = value; }
    else if (setting == WM_SCAN) f->scan = value;
    else f->sleep_return = value;
    return 0;
}
int main(void) {
    struct wm_profile original;
    struct fake f = {2, 0, 200, 0, 0, 0};
    assert(wm_snapshot(&f, read_fake, &original));
    assert(wm_enforce(&f, read_fake, write_fake) && f.pm == 0 && f.scan == 1);
    assert(wm_restore(&f, read_fake, write_fake, original) && f.pm == 2 && f.scan == 0 && f.sleep_return == 200);
    f = (struct fake){0, 1, 2000, 0, 0, 0};
    assert(wm_snapshot(&f, read_fake, &original));
    assert(wm_enforce(&f, read_fake, write_fake));
    assert(wm_restore(&f, read_fake, write_fake, original) && f.pm == 0 && f.scan == 1);
    f = (struct fake){2, 0, 200, 1, 0, 0};
    assert(!wm_enforce(&f, read_fake, write_fake) && f.pm == 2 && f.scan == 0);
    f = (struct fake){2, 0, 200, 0, 2, 0}; original = (struct wm_profile){2, 0, 200};
    assert(!wm_enforce(&f, read_fake, write_fake) && f.pm == 0 && f.scan == 0);
    assert(wm_restore(&f, read_fake, write_fake, original) && f.pm == 2 && f.scan == 0);
    f = (struct fake){1, 0, 1000, 0, 0, 0};
    assert(wm_restore(&f, read_fake, write_fake, (struct wm_profile){2, 0, 200}) && f.pm == 1 && f.sleep_return == 1000);
    f = (struct fake){0, 1, 2000, 0, 2, 0};
    assert(!wm_restore(&f, read_fake, write_fake, (struct wm_profile){2, 0, 200}));
    f = (struct fake){2, 0, 200, 0, 0, 0};
    assert(wm_enforce(&f, read_fake, write_fake));
    f.pm = 2; f.scan = 0;
    assert(wm_enforce(&f, read_fake, write_fake) && f.pm == 0 && f.scan == 1);
    assert(!wm_restore(&f, read_fake, write_fake, (struct wm_profile){99, -1, 200}));
    f = (struct fake){2, 0, 200, 0, 0, 1}; original = (struct wm_profile){2, 0, 200};
    assert(wm_enforce(&f, read_fake, write_fake) && f.pm == 2 && f.sleep_return == 2000);
    assert(wm_restore(&f, read_fake, write_fake, original) && f.pm == 2 && f.scan == 0 && f.sleep_return == 200);
    f = (struct fake){2, 0, 200, 0, 1, 0};
    assert(wm_enforce(&f, read_fake, write_fake) && f.pm == 2 && f.sleep_return == 2000);
    f = (struct fake){0, 1, 5000, 0, 0, 0};
    assert(wm_enforce(&f, read_fake, write_fake) && f.sleep_return == 5000);
    puts("WM network guard policy: 11 scenarios passed");
    return 0;
}
