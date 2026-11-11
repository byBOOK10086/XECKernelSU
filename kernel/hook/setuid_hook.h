#ifndef __KSU_H_KSU_CORE
#define __KSU_H_KSU_CORE

#include <linux/init.h>
#include <linux/types.h>

void ksu_setuid_hook_init(void);
void ksu_setuid_hook_exit(void);

// Handler functions for hook_manager
#ifdef CONFIG_KSU_SUSFS
/*
 * SUSFS mode: the baseline kernel's setresuid hook (50_add_susfs) passes all three ids,
 * because the branch needs the real uid zygote switches to, and it tells zygote from
 * zygote_next through the sid cache in selinux/selinux.c.
 */
int ksu_handle_setresuid(uid_t ruid, uid_t euid, uid_t suid);
#else
int ksu_handle_setresuid(uid_t old_uid, uid_t new_uid);
#endif

#endif
