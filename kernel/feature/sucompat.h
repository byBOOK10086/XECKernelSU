#ifndef __KSU_H_SUCOMPAT
#define __KSU_H_SUCOMPAT
#include <asm/ptrace.h>
#include <linux/types.h>

#ifdef CONFIG_KSU_SUSFS
/*
 * SUSFS mode: the switch is a static key (the baseline kernel's fs/exec.c and fs/stat.c
 * hooks read it with static_branch_likely), and the handlers are called from those
 * source-level hooks, so they take `struct filename` instead of a pt_regs.
 *
 * These signatures must match the extern declarations inside the 50_add_susfs patch.
 */
extern struct static_key_true ksu_su_compat_enabled;

int ksu_handle_execveat(int *fd, struct filename **filename_ptr, void *argv, void *envp, int *flags);
int ksu_handle_execveat_sucompat(int *fd, struct filename **filename_ptr, void *argv_user, void *envp_user,
                                 int *__never_use_flags);
int ksu_handle_post_execveat_sucompat(int *fd, struct filename **filename_ptr, void *argv_user, void *envp_user,
                                      int *__never_use_flags, int *retval);
int ksu_handle_faccessat(int *dfd, struct filename **filename, int *mode, int *__unused_flags);
int ksu_handle_stat(int *dfd, struct filename **filename, int *flags);
#else
extern bool ksu_su_compat_enabled;

// Handler functions exported for hook_manager
long ksu_handle_faccessat_sucompat(int orig_nr, struct pt_regs *regs);
long ksu_handle_stat_sucompat(int orig_nr, struct pt_regs *regs);
long ksu_handle_execve_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs);
long ksu_handle_execveat_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs);
#endif /* CONFIG_KSU_SUSFS */

void ksu_sucompat_init(void);
void ksu_sucompat_exit(void);

#endif
