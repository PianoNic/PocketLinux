/*
 * tmp-redirect: sends paths under /tmp to $TMPDIR, for glibc programs only.
 *
 * Android has no writable /tmp. Most programs honor $TMPDIR, but the .NET runtime puts
 * the files behind named mutexes under a hardcoded /tmp/.dotnet, so `dotnet new`,
 * `dotnet build` and NuGet fail with mkdtemp("/tmp/.dotnet.XXXXXX") == EACCES.
 *
 * Loaded through $PREFIX/glibc/etc/ld.so.preload, so it applies to glibc programs
 * (.NET) and never to the normal (bionic) Termux programs.
 *
 * Build (see build-in-wsl.sh):
 *   aarch64-linux-gnu-gcc -O2 -shared -fPIC -o libtmp-redirect.so tmp-redirect.c -ldl
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define FALLBACK_TMPDIR "/data/data/com.termux/files/usr/tmp" /* relocated with the app */

static const char *tmpdir(void) {
    const char *t = getenv("TMPDIR");
    return (t && *t == '/') ? t : FALLBACK_TMPDIR;
}

/* Returns path, or a rewritten copy in buf when path is /tmp or below /tmp/. */
static const char *fix(const char *path, char *buf) {
    if (!path || strncmp(path, "/tmp", 4) != 0 || (path[4] != '\0' && path[4] != '/'))
        return path;
    const char *t = tmpdir();
    if (strncmp(t, "/tmp", 4) == 0 && (t[4] == '\0' || t[4] == '/'))
        return path; /* TMPDIR is /tmp itself: nothing to do */
    if (snprintf(buf, PATH_MAX, "%s%s", t, path + 4) >= PATH_MAX)
        return path;
    return buf;
}

#define REAL(ret, name, ...) \
    static ret (*real)(__VA_ARGS__); \
    if (!real) real = (ret (*)(__VA_ARGS__)) dlsym(RTLD_NEXT, name)

/* ---- open family ---------------------------------------------------------------------- */

static mode_t va_mode(int flags, va_list ap) {
    return (flags & (O_CREAT | O_TMPFILE)) ? (mode_t) va_arg(ap, int) : 0;
}

int open(const char *p, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t m = va_mode(flags, ap); va_end(ap);
    char b[PATH_MAX]; REAL(int, "open", const char *, int, ...);
    return real(fix(p, b), flags, m);
}
int open64(const char *p, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t m = va_mode(flags, ap); va_end(ap);
    char b[PATH_MAX]; REAL(int, "open64", const char *, int, ...);
    return real(fix(p, b), flags, m);
}
int openat(int d, const char *p, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t m = va_mode(flags, ap); va_end(ap);
    char b[PATH_MAX]; REAL(int, "openat", int, const char *, int, ...);
    return real(d, fix(p, b), flags, m);
}
int openat64(int d, const char *p, int flags, ...) {
    va_list ap; va_start(ap, flags); mode_t m = va_mode(flags, ap); va_end(ap);
    char b[PATH_MAX]; REAL(int, "openat64", int, const char *, int, ...);
    return real(d, fix(p, b), flags, m);
}
int creat(const char *p, mode_t m) {
    char b[PATH_MAX]; REAL(int, "creat", const char *, mode_t);
    return real(fix(p, b), m);
}
FILE *fopen(const char *p, const char *mode) {
    char b[PATH_MAX]; REAL(FILE *, "fopen", const char *, const char *);
    return real(fix(p, b), mode);
}
FILE *fopen64(const char *p, const char *mode) {
    char b[PATH_MAX]; REAL(FILE *, "fopen64", const char *, const char *);
    return real(fix(p, b), mode);
}

/* ---- directories ---------------------------------------------------------------------- */

int mkdir(const char *p, mode_t m) {
    char b[PATH_MAX]; REAL(int, "mkdir", const char *, mode_t);
    return real(fix(p, b), m);
}
int mkdirat(int d, const char *p, mode_t m) {
    char b[PATH_MAX]; REAL(int, "mkdirat", int, const char *, mode_t);
    return real(d, fix(p, b), m);
}
int rmdir(const char *p) {
    char b[PATH_MAX]; REAL(int, "rmdir", const char *);
    return real(fix(p, b));
}
DIR *opendir(const char *p) {
    char b[PATH_MAX]; REAL(DIR *, "opendir", const char *);
    return real(fix(p, b));
}

/* mkdtemp/mkstemp fill in the caller's template, which must keep its length: run the call
 * on the redirected path, then copy only the random XXXXXX part back into the template. */
static int copy_suffix(char *tmpl, const char *done) {
    size_t n = strlen(tmpl), m = strlen(done);
    if (n < 6 || m < 6) return -1;
    memcpy(tmpl + n - 6, done + m - 6, 6);
    return 0;
}
char *mkdtemp(char *tmpl) {
    REAL(char *, "mkdtemp", char *);
    char b[PATH_MAX]; const char *f = fix(tmpl, b);
    if (f == tmpl) return real(tmpl);
    if (!real(b)) return NULL;
    return copy_suffix(tmpl, b) == 0 ? tmpl : NULL;
}
int mkstemp(char *tmpl) {
    REAL(int, "mkstemp", char *);
    char b[PATH_MAX]; const char *f = fix(tmpl, b);
    if (f == tmpl) return real(tmpl);
    int fd = real(b);
    if (fd >= 0) copy_suffix(tmpl, b);
    return fd;
}
int mkstemp64(char *tmpl) { return mkstemp(tmpl); }

/* ---- files ------------------------------------------------------------------------------ */

int unlink(const char *p) {
    char b[PATH_MAX]; REAL(int, "unlink", const char *);
    return real(fix(p, b));
}
int unlinkat(int d, const char *p, int fl) {
    char b[PATH_MAX]; REAL(int, "unlinkat", int, const char *, int);
    return real(d, fix(p, b), fl);
}
int rename(const char *a, const char *z) {
    char b1[PATH_MAX], b2[PATH_MAX]; REAL(int, "rename", const char *, const char *);
    return real(fix(a, b1), fix(z, b2));
}
int renameat(int da, const char *a, int dz, const char *z) {
    char b1[PATH_MAX], b2[PATH_MAX]; REAL(int, "renameat", int, const char *, int, const char *);
    return real(da, fix(a, b1), dz, fix(z, b2));
}
int access(const char *p, int m) {
    char b[PATH_MAX]; REAL(int, "access", const char *, int);
    return real(fix(p, b), m);
}
int faccessat(int d, const char *p, int m, int fl) {
    char b[PATH_MAX]; REAL(int, "faccessat", int, const char *, int, int);
    return real(d, fix(p, b), m, fl);
}
int chmod(const char *p, mode_t m) {
    char b[PATH_MAX]; REAL(int, "chmod", const char *, mode_t);
    return real(fix(p, b), m);
}
int fchmodat(int d, const char *p, mode_t m, int fl) {
    char b[PATH_MAX]; REAL(int, "fchmodat", int, const char *, mode_t, int);
    return real(d, fix(p, b), m, fl);
}
int truncate(const char *p, off_t len) {
    char b[PATH_MAX]; REAL(int, "truncate", const char *, off_t);
    return real(fix(p, b), len);
}
char *realpath(const char *p, char *resolved) {
    char b[PATH_MAX]; REAL(char *, "realpath", const char *, char *);
    return real(fix(p, b), resolved);
}

/* stat family: glibc >= 2.33 exports these directly. */
int stat(const char *p, struct stat *st) {
    char b[PATH_MAX]; REAL(int, "stat", const char *, struct stat *);
    return real(fix(p, b), st);
}
int lstat(const char *p, struct stat *st) {
    char b[PATH_MAX]; REAL(int, "lstat", const char *, struct stat *);
    return real(fix(p, b), st);
}
int stat64(const char *p, struct stat64 *st) {
    char b[PATH_MAX]; REAL(int, "stat64", const char *, struct stat64 *);
    return real(fix(p, b), st);
}
int lstat64(const char *p, struct stat64 *st) {
    char b[PATH_MAX]; REAL(int, "lstat64", const char *, struct stat64 *);
    return real(fix(p, b), st);
}
int fstatat(int d, const char *p, struct stat *st, int fl) {
    char b[PATH_MAX]; REAL(int, "fstatat", int, const char *, struct stat *, int);
    return real(d, fix(p, b), st, fl);
}
int fstatat64(int d, const char *p, struct stat64 *st, int fl) {
    char b[PATH_MAX]; REAL(int, "fstatat64", int, const char *, struct stat64 *, int);
    return real(d, fix(p, b), st, fl);
}
int statx(int d, const char *p, int fl, unsigned int mask, struct statx *st) {
    char b[PATH_MAX]; REAL(int, "statx", int, const char *, int, unsigned int, struct statx *);
    return real(d, fix(p, b), fl, mask, st);
}
