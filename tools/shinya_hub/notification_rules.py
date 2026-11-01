"""Versioned independent reminder rules. Existing enable means device only."""
CATEGORIES = ('enable', 'game', 'video', 'payment', 'social')


def merge_rule(previous, raw, now):
    previous = previous if isinstance(previous, dict) else {}
    for name in CATEGORIES:
        if name in raw and type(raw[name]) is not bool:
            raise ValueError('通知开关必须为布尔值')
    values = {name: raw.get(name, previous.get(name, False)) is True for name in CATEGORIES}
    versions = previous.get('categoryUpdatedAt') or {}
    from .notification_preferences import configuration, validate_apps
    configs = configuration(previous.get('settings'), raw.get('settings'))
    if 'settings' in raw: validate_apps(configs)
    old_configs = configuration(previous.get('settings'))
    changed_categories = {name for name in CATEGORIES if values[name] != (previous.get(name) is True) or configs[name] != old_configs[name]}
    changed = any(values[name] != (previous.get(name) is True) for name in CATEGORIES) or bool(changed_categories)
    return {**previous, **values, 'schemaVersion': 2, 'independentRules': True,
            'settings': configs, 'revision': int(previous.get('revision') or 0) + int(changed),
            'updatedAt': now if changed else previous.get('updatedAt') or now,
            'categoryUpdatedAt': {name: now if name in changed_categories
                else versions.get(name) or previous.get('updatedAt') or now for name in CATEGORIES}}
