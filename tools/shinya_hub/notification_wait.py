"""Bounded long-poll wakeup; no background HTTP polling loop on the phone."""
import threading
import time

_changed = threading.Condition()
_slots = threading.BoundedSemaphore(3)


def changed():
    with _changed:
        _changed.notify_all()


def wait_for_feed(query, seconds=15):
    if type(seconds) is not int or not 0 <= seconds <= 15:
        raise ValueError('通知等待时长无效')
    if not _slots.acquire(blocking=False):
        # Keep the remaining HTTP worker threads available for ordinary UI.
        raise LookupError('通知连接繁忙，请稍后重试')
    try:
        deadline = time.monotonic()+seconds
        with _changed:
            while True:
                result = query()  # Re-authorize the current projects on every wake.
                if result.get('items') or time.monotonic() >= deadline:
                    return result
                _changed.wait(max(0, deadline-time.monotonic()))
    finally:
        _slots.release()
