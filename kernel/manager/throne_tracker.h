#ifndef __KSU_H_UID_OBSERVER
#define __KSU_H_UID_OBSERVER

#include <linux/types.h>
#ifdef CONFIG_KSU_DISABLE_MANAGER
static inline void ksu_throne_tracker_init()
{
}

static inline void ksu_throne_tracker_exit()
{
}

static inline void track_throne(bool prune_only)
{
    (void)prune_only;
}

static inline void ksu_recrown_manager_if_needed()
{
}
#else
void ksu_throne_tracker_init();

void ksu_throne_tracker_exit();

void track_throne(bool prune_only);

/* Retry the crowning while the manager asks for kernel info and no appid is
 * known yet; rate-limited inside, a no-op once the manager is crowned. */
void ksu_recrown_manager_if_needed(void);
#endif

#endif
