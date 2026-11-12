#ifndef __KSU_H_KSUD
#define __KSU_H_KSUD

#include <asm/syscall.h>

#include <linux/compat.h>
#include <linux/uaccess.h>

// argv/envp of an exec syscall, laid out exactly like fs/exec.c's struct user_arg_ptr:
// the 50_add_susfs fs/exec.c hook hands &argv/&envp straight to the SUSFS handlers, which
// cast them back to this type.
struct user_arg_ptr {
#ifdef CONFIG_COMPAT
    bool is_compat;
#endif
    union {
        const char __user *const __user *native;
#ifdef CONFIG_COMPAT
        const compat_uptr_t __user *compat;
#endif
    } ptr;
};

#define KSUD_PATH "/data/adb/ksud"

void ksu_ksud_init();
void ksu_ksud_exit();

const char __user *get_user_arg_ptr(struct user_arg_ptr argv, int nr);

// Shared by both modes: the LKM execve hook and the SUSFS ksu_handle_execveat() chain.
void ksu_handle_execveat_ksud(const char *path, struct user_arg_ptr *argv);

void ksu_execve_hook_ksud(const struct pt_regs *regs);
void ksu_execveat_hook_ksud(const struct pt_regs *regs);
void ksu_stop_input_hook_runtime(void);

#endif
