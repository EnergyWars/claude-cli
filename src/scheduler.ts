import { Cron } from 'croner';

import type { SchedulerConfig } from './config.js';

export interface SchedulerRegistry {
  stop: () => void;
}

export function startSchedulers(
  schedulers: SchedulerConfig[],
  onTrigger: (scheduler: SchedulerConfig) => void,
): SchedulerRegistry {
  const jobs = schedulers.flatMap((scheduler) => {
    try {
      return [
        new Cron(scheduler.cron, () => {
          onTrigger(scheduler);
        }),
      ];
    } catch (error) {
      console.error(
        `Scheduler "${scheduler.name}": ungueltiger Cron-Ausdruck "${scheduler.cron}" - ${error instanceof Error ? error.message : String(error)}`,
      );
      return [];
    }
  });

  return {
    stop: () => {
      for (const job of jobs) {
        job.stop();
      }
    },
  };
}
