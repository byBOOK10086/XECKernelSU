// Link-compatibility layer for the susfs4ksu GKI patch.
//
// The 50_add_susfs_in_gki-<KMI>.patch is generated against the SukiSU/ReSukiSU
// flavour of KernelSU and therefore calls three helpers that this tree spells
// differently. Keep the kernel linking with the smallest honest definitions
// instead of pulling in a whole subsystem we do not ship:
//
//   ksu_handle_sys_read(fd)            SukiSU hooks sys_read and installs the
//                                      read hook here. Our tree installs the same
//                                      hook one level down, from the vfs_read hook
//                                      (ksu_handle_vfs_read -> the static
//                                      ksu_handle_sys_read_ex), so this entry
//                                      point has nothing left to do.
//   ksu_setup_preinit_file(...)        part of the preinit integration. We have
//   ksu_preinit_handle_post_execveat   our own preinit (preinit/preinit.c) with a
//                                      different API; declining here makes the
//                                      patch fall back to the normal exec path.
//
// Everything is guarded by CONFIG_KSU_SUSFS: without it the patch is not applied
// and none of these symbols are referenced.

#include <linux/compiler_types.h>
#include <linux/fs.h>
#include <linux/types.h>

#include "klog.h" // IWYU pragma: keep
#include "runtime/ksud.h"

#ifdef CONFIG_KSU_SUSFS

void ksu_handle_sys_read(unsigned int fd)
{
    // See the file header: our vfs_read hook already installed the rc hook.
    (void)fd;
}

int ksu_setup_preinit_file(struct filename **filename_ptr, struct user_arg_ptr *argv_user)
{
    (void)filename_ptr;
    (void)argv_user;
    return -1; // nothing prepared: the patch keeps the unmodified exec path
}

int ksu_preinit_handle_post_execveat(int *fd, struct filename **filename_ptr, void *argv_user, void *envp_user,
                                     int *flags, int *retval, int *preinit_fd)
{
    (void)fd;
    (void)filename_ptr;
    (void)argv_user;
    (void)envp_user;
    (void)flags;
    (void)retval;
    (void)preinit_fd;
    return 0; // not handled: let exec continue normally
}

#endif // CONFIG_KSU_SUSFS
