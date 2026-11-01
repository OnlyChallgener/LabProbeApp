"""Versioned independent reminder rules. Existing enable means device only."""
CATEGORIES = ('enable', 'game', 'video', 'payment', 'social')


def merge_rule(previous, raw, now):
    previous = previous if isinstance(previous, dict) else {}
    for name in CATEGORIES:
        if name in raw and type(raw[name]) is not bool:
            raise ValueError('通知开关必须为布尔值')
    values = {name: raw.get(name, previous.get(name, False)) is True for name in CATEGORIES}
    versions = previous.get('categoryUpdatedAt') or {}
    changed = any(values[name] != (previous.get(name) is True) for name in CATEGORIES)
    return {**previous, **values, 'schemaVersion': 2, 'independentRules': True,
            'revision': int(previous.get('revision') or 0) + int(changed),
            'updatedAt': now if changed else previous.get('updatedAt') or now,
            'categoryUpdatedAt': {name: now if values[name] != (previous.get(name) is True)
                else versions.get(name) or previous.get('updatedAt') or now for name in CATEGORIES}}
