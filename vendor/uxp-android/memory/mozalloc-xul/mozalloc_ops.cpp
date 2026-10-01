/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * Out-of-line operator new/delete for libxul on Android.
 *
 * mozalloc.h always-inlines the operator new/delete bodies, but some
 * translation units still emit out-of-line _Znwm/_Znam/_ZdlPv/_ZdaPv
 * references. Those resolve to libmozglue's weak mozalloc definitions,
 * which allocate through *bionic* malloc -- while libxul's free is its
 * own jemalloc. Freeing a bionic chunk with jemalloc crashes, so libxul
 * needs its own operators backed by the same allocator as its free.
 */
#include <stdlib.h>
#include <new>

extern "C" void* moz_xmalloc(size_t size);

void* operator new(std::size_t size)
{
    return moz_xmalloc(size);
}

void* operator new[](std::size_t size)
{
    return moz_xmalloc(size);
}

void* operator new(std::size_t size, const std::nothrow_t&) noexcept
{
    return malloc(size);
}

void* operator new[](std::size_t size, const std::nothrow_t&) noexcept
{
    return malloc(size);
}

void operator delete(void* ptr) noexcept
{
    free(ptr);
}

void operator delete[](void* ptr) noexcept
{
    free(ptr);
}

void operator delete(void* ptr, const std::nothrow_t&) noexcept
{
    free(ptr);
}

void operator delete[](void* ptr, const std::nothrow_t&) noexcept
{
    free(ptr);
}
