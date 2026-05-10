/*
 * LD_PRELOAD hook for Android Bionic: rewrite legacy package paths baked into WINE/imagefs
 * (com.winlator.cmod, com.winlator, app.gamenative) to the host app's private data directory.
 *
 * Target path is read from GAMENATIVE_HOST_DATA_DIR (set by BionicProgramLauncherComponent),
 * typically Context.getDataDir() e.g. /data/user/0/app.gamenative.mesa
 */

#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define LOG_TAG "RedirectBionic"
#define MAYBE_LOG(...)                                                                             \
    do {                                                                                           \
        if (getenv("GAMENATIVE_REDIRECT_DEBUG"))                                                   \
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__);                           \
    } while (0)

#define MAXPATH 4096

typedef struct {
    const char *pfx;
    size_t len;
} LegacyPrefix;

/* Longest match must appear first (com.winlator.cmod before com.winlator). */
static const LegacyPrefix LEGACY[] = {
    {"/data/user/0/com.winlator.cmod", 31},
    {"/data/data/com.winlator.cmod", 28},
    {"/data/user/0/com.winlator", 26},
    {"/data/data/com.winlator", 23},
    {"/data/user/0/app.gamenative", 27},
    {"/data/data/app.gamenative", 24},
};

static const char *target_dir(void) {
    const char *e = getenv("GAMENATIVE_HOST_DATA_DIR");
    return (e && *e) ? e : NULL;
}

static const char *rewrite(const char *path, char *buf, size_t buflen) {
    if (!path || !buf || buflen < 2)
        return path;
    const char *target = target_dir();
    if (!target)
        return path;

    size_t plen = strlen(path);
    size_t tlen = strlen(target);
    for (size_t i = 0; i < sizeof(LEGACY) / sizeof(LEGACY[0]); i++) {
        size_t L = LEGACY[i].len;
        if (plen < L)
            continue;
        if (memcmp(path, LEGACY[i].pfx, L) != 0)
            continue;
        if (path[L] != '/' && path[L] != '\0')
            continue;
        if (tlen + (plen - L) + 1 > buflen)
            return path;
        memcpy(buf, target, tlen);
        memcpy(buf + tlen, path + L, plen - L + 1);
        MAYBE_LOG("rewrite %s -> %s", path, buf);
        return buf;
    }
    return path;
}

typedef int (*openat_fn)(int, const char *, int, ...);
typedef int (*rename_fn)(const char *, const char *);
typedef int (*unlink_fn)(const char *);
typedef int (*access_fn)(const char *, int);
typedef int (*chmod_fn)(const char *, mode_t);
typedef int (*mkdir_fn)(const char *, mode_t);
typedef FILE *(*fopen_fn)(const char *, const char *);
typedef char *(*realpath_fn)(const char *, char *);

static openat_fn real_openat;
static rename_fn real_rename;
static unlink_fn real_unlink;
static access_fn real_access;
static chmod_fn real_chmod;
static mkdir_fn real_mkdir;
static fopen_fn real_fopen;
static realpath_fn real_realpath;

static void resolve_once(void) {
    static int done;
    if (done)
        return;
    real_openat = (openat_fn)dlsym(RTLD_NEXT, "openat");
    real_rename = (rename_fn)dlsym(RTLD_NEXT, "rename");
    real_unlink = (unlink_fn)dlsym(RTLD_NEXT, "unlink");
    real_access = (access_fn)dlsym(RTLD_NEXT, "access");
    real_chmod = (chmod_fn)dlsym(RTLD_NEXT, "chmod");
    real_mkdir = (mkdir_fn)dlsym(RTLD_NEXT, "mkdir");
    real_fopen = (fopen_fn)dlsym(RTLD_NEXT, "fopen");
    real_realpath = (realpath_fn)dlsym(RTLD_NEXT, "realpath");
    done = 1;
}

int openat(int dirfd, const char *pathname, int flags, ...) {
    resolve_once();
    char buf[MAXPATH];
    const char *p = rewrite(pathname, buf, sizeof buf);

    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = (mode_t)va_arg(ap, int);
        va_end(ap);
        return real_openat(dirfd, p, flags, mode);
    }
    return real_openat(dirfd, p, flags);
}

int open(const char *pathname, int flags, ...) {
    resolve_once();
    char buf[MAXPATH];
    const char *p = rewrite(pathname, buf, sizeof buf);
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = (mode_t)va_arg(ap, int);
        va_end(ap);
        return real_openat(AT_FDCWD, p, flags, mode);
    }
    return real_openat(AT_FDCWD, p, flags);
}

FILE *fopen(const char *pathname, const char *mode) {
    resolve_once();
    char buf[MAXPATH];
    const char *p = rewrite(pathname, buf, sizeof buf);
    return real_fopen(p, mode);
}

FILE *freopen(const char *pathname, const char *mode, FILE *stream) {
    resolve_once();
    typedef FILE *(*freopen_fn)(const char *, const char *, FILE *);
    static freopen_fn rf;
    if (!rf)
        rf = (freopen_fn)dlsym(RTLD_NEXT, "freopen");
    char buf[MAXPATH];
    const char *p = pathname ? rewrite(pathname, buf, sizeof buf) : pathname;
    return rf(p, mode, stream);
}

int access(const char *pathname, int mode) {
    resolve_once();
    char buf[MAXPATH];
    const char *p = rewrite(pathname, buf, sizeof buf);
    return real_access(p, mode);
}

int chmod(const char *pathname, mode_t mode) {
    resolve_once();
    char buf[MAXPATH];
    const char *p = rewrite(pathname, buf, sizeof buf);
    return real_chmod(p, mode);
}

int mkdir(const char *pathname, mode_t mode) {
    resolve_once();
    char buf[MAXPATH];
    const char *p = rewrite(pathname, buf, sizeof buf);
    return real_mkdir(p, mode);
}

int rename(const char *oldpath, const char *newpath) {
    resolve_once();
    char oldb[MAXPATH], newb[MAXPATH];
    const char *o = rewrite(oldpath, oldb, sizeof oldb);
    const char *n = rewrite(newpath, newb, sizeof newb);
    return real_rename(o, n);
}

int unlink(const char *pathname) {
    resolve_once();
    char buf[MAXPATH];
    const char *p = rewrite(pathname, buf, sizeof buf);
    return real_unlink(p);
}

int stat(const char *pathname, struct stat *st) {
    resolve_once();
    typedef int (*stat_fn)(const char *, struct stat *);
    static stat_fn rs;
    if (!rs)
        rs = (stat_fn)dlsym(RTLD_NEXT, "stat");
    char buf[MAXPATH];
    const char *p = rewrite(pathname, buf, sizeof buf);
    return rs(p, st);
}

int lstat(const char *pathname, struct stat *st) {
    resolve_once();
    typedef int (*lstat_fn)(const char *, struct stat *);
    static lstat_fn rl;
    if (!rl)
        rl = (lstat_fn)dlsym(RTLD_NEXT, "lstat");
    char buf[MAXPATH];
    const char *p = rewrite(pathname, buf, sizeof buf);
    return rl(p, st);
}

int fstatat(int dirfd, const char *pathname, struct stat *st, int flags) {
    resolve_once();
    typedef int (*fstatat_fn)(int, const char *, struct stat *, int);
    static fstatat_fn rf;
    if (!rf)
        rf = (fstatat_fn)dlsym(RTLD_NEXT, "fstatat");
    char buf[MAXPATH];
    const char *p = pathname ? rewrite(pathname, buf, sizeof buf) : pathname;
    return rf(dirfd, p, st, flags);
}

ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) {
    resolve_once();
    typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
    static readlink_fn rr;
    if (!rr)
        rr = (readlink_fn)dlsym(RTLD_NEXT, "readlink");
    char tmp[MAXPATH];
    const char *p = rewrite(pathname, tmp, sizeof tmp);
    return rr(p, buf, bufsiz);
}

int symlink(const char *target, const char *linkpath) {
    resolve_once();
    typedef int (*symlink_fn)(const char *, const char *);
    static symlink_fn rs;
    if (!rs)
        rs = (symlink_fn)dlsym(RTLD_NEXT, "symlink");
    char tb[MAXPATH], lb[MAXPATH];
    const char *t = rewrite(target, tb, sizeof tb);
    const char *l = rewrite(linkpath, lb, sizeof lb);
    return rs(t, l);
}

char *realpath(const char *path, char *resolved_path) {
    resolve_once();
    char tmp[MAXPATH];
    const char *p = path ? rewrite(path, tmp, sizeof tmp) : path;
    return real_realpath(p, resolved_path);
}
