/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * A second compilation of mozalloc.cpp, this time into libxul.
 *
 * On Android, libxul's own jemalloc provides malloc/free/calloc etc. as
 * @@xul6 definitions, while operator new/delete and moz_x* come from
 * mozglue's mozalloc and resolve to *bionic* malloc. Allocating with one
 * allocator and freeing with the other crashes. Giving libxul its own
 * copy of the mozalloc entry points makes every allocation family inside
 * libxul self-consistent on libxul's jemalloc.
 */
#include "../mozalloc/mozalloc.cpp"
