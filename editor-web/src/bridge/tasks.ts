import { rpc, type RpcError } from "./rpc";

interface TaskStart {
  taskId?: string;
  cancelled?: boolean;
}

interface TaskCompletion<T = unknown> {
  taskId: string;
  kind: string;
  result?: T;
  error?: RpcError;
}

type TaskWaiter = {
  resolve: (value: unknown) => void;
  reject: (reason: Error) => void;
  timer: number;
};

class TaskClient {
  private readonly completed = new Map<string, TaskCompletion>();
  private readonly waiters = new Map<string, TaskWaiter>();

  constructor() {
    rpc.on("task.completed", (raw) => this.complete(raw as TaskCompletion));
  }

  async request<T>(type: string, payload: unknown, timeoutMs = 10 * 60_000): Promise<T | undefined> {
    const started = await rpc.request<TaskStart>(type, payload);
    if (started.cancelled || !started.taskId) return undefined;
    return this.wait<T>(started.taskId, timeoutMs);
  }

  private wait<T>(taskId: string, timeoutMs: number): Promise<T> {
    const ready = this.completed.get(taskId);
    if (ready) {
      this.completed.delete(taskId);
      return Promise.resolve().then(() => this.result<T>(ready));
    }
    return new Promise<T>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.waiters.delete(taskId);
        reject(new Error("后台任务等待超时"));
      }, timeoutMs);
      this.waiters.set(taskId, { resolve: resolve as (value: unknown) => void, reject, timer });
    });
  }

  private complete(completion: TaskCompletion): void {
    const waiter = this.waiters.get(completion.taskId);
    if (!waiter) {
      this.completed.set(completion.taskId, completion);
      return;
    }
    this.waiters.delete(completion.taskId);
    window.clearTimeout(waiter.timer);
    try {
      waiter.resolve(this.result(completion));
    } catch (error) {
      waiter.reject(error instanceof Error ? error : new Error(String(error)));
    }
  }

  private result<T>(completion: TaskCompletion): T {
    if (completion.error) {
      const error = new Error(completion.error.message);
      Object.assign(error, { code: completion.error.code, details: completion.error.details });
      throw error;
    }
    return completion.result as T;
  }
}

export const tasks = new TaskClient();
