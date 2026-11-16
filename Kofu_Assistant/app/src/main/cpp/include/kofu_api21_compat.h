#pragma once

#if defined(__ANDROID__) && defined(__ANDROID_API__) && (__ANDROID_API__ < 23)
#include <sys/mman.h>
#include <stddef.h>

#ifndef POSIX_MADV_NORMAL
#define POSIX_MADV_NORMAL     MADV_NORMAL
#define POSIX_MADV_RANDOM     MADV_RANDOM
#define POSIX_MADV_SEQUENTIAL MADV_SEQUENTIAL
#define POSIX_MADV_WILLNEED   MADV_WILLNEED
#define POSIX_MADV_DONTNEED   MADV_DONTNEED
#endif

static inline int kofu_posix_madvise_compat(void* addr, size_t len, int advice) {
    return madvise(addr, len, advice);
}

#ifndef posix_madvise
#define posix_madvise(addr, len, advice) kofu_posix_madvise_compat((addr), (len), (advice))
#endif
#endif
