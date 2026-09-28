// Preload library for the desktop session (bionic, not glibc despite the folder name): the
// panel's start menu shows the phone's name instead of the Android user ID (u0_a123).
//
// GLib ignores the passwd "gecos" field on Android, so the menu always shows the login name.
// This library changes that name, but only inside the panel (which draws the menu): anywhere
// else a login name like "S25 Ultra von Niclas" would break ls, ssh and scripts. Other
// programs load it too, through the inherited LD_PRELOAD, and it does nothing there.
// The name comes from POCKET_DEVICE_NAME, set by bin/desktop.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pwd.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static struct passwd *named(struct passwd *pw) {
    static int in_panel = -1;
    if (in_panel < 0) {
        const char *prog = getprogname();
        in_panel = prog && (!strcmp(prog, "xfce4-panel") || !strcmp(prog, "wrapper-2.0"));
    }
    const char *name = getenv("POCKET_DEVICE_NAME");
    if (in_panel && pw && name && *name && pw->pw_uid == getuid()) pw->pw_name = (char *) name;
    return pw;
}

struct passwd *getpwuid(uid_t uid) {
    static struct passwd *(*real)(uid_t);
    if (!real) real = dlsym(RTLD_NEXT, "getpwuid");
    return named(real(uid));
}

struct passwd *getpwnam(const char *login) {
    static struct passwd *(*real)(const char *);
    if (!real) real = dlsym(RTLD_NEXT, "getpwnam");
    return named(real(login));
}

int getpwuid_r(uid_t uid, struct passwd *pw, char *buf, size_t len, struct passwd **result) {
    static int (*real)(uid_t, struct passwd *, char *, size_t, struct passwd **);
    if (!real) real = dlsym(RTLD_NEXT, "getpwuid_r");
    int r = real(uid, pw, buf, len, result);
    if (r == 0 && result) named(*result);
    return r;
}

int getpwnam_r(const char *login, struct passwd *pw, char *buf, size_t len, struct passwd **result) {
    static int (*real)(const char *, struct passwd *, char *, size_t, struct passwd **);
    if (!real) real = dlsym(RTLD_NEXT, "getpwnam_r");
    int r = real(login, pw, buf, len, result);
    if (r == 0 && result) named(*result);
    return r;
}
