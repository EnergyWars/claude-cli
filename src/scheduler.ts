import { Cron } from 'croner';

export interface SchedulerRegistry {
  stop: () => void;
}

export function startSchedulers<T extends { name: string; cron: string }>(
  schedulers: T[],
  onTrigger: (scheduler: T) => void,
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
