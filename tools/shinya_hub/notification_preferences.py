"""Per-device reminder drafts, validated against the shared child catalog."""
import copy

CATEGORIES = ('enable', 'game', 'video', 'payment', 'social')


def defaults():
    return {
        'enable': {'start': True, 'end': True, 'night': True, 'long': True, 'longMinutes': 120},
        'game': {'allApps': True, 'appIds': [], 'durationEnabled': True, 'minutes': 10},
        'video': {'allApps': True, 'appIds': [], 'durationEnabled': True, 'minutes': 10},
        'social': {'allApps': True, 'appIds': [], 'durationEnabled': True, 'minutes': 30},
        'payment': {'allServices': True, 'appIds': []},
    }


def configuration(previous=None, updates=None):
    result = defaults()
    previous = previous if isinstance(previous, dict) else {}
    updates = {} if updates is None else updates
    if not isinstance(updates, dict) or set(updates) - set(CATEGORIES):
        raise ValueError('提醒分类设置无效')
    for category, template in result.items():
        old = previous.get(category)
        if isinstance(old, dict):
            for key in template:
                if key in old: template[key] = copy.deepcopy(old[key])
        patch = updates.get(category, {})
        if not isinstance(patch, dict) or set(patch) - set(template):
            raise ValueError('提醒设置字段无效')
        template.update(copy.deepcopy(patch))
        for key, value in template.items():
            if key in ('minutes', 'longMinutes'):
                if type(value) is not int or not 1 <= value <= 1440:
                    raise ValueError('时长请输入1—1440的整数分钟')
            elif key == 'appIds':
                if not isinstance(value, list) or len(value) > 512 or any(
                    not isinstance(v, str) or not v or len(v) > 128 for v in value
                ):
                    raise ValueError('关注应用无效')
                template[key] = sorted(set(value))
            elif type(value) is not bool:
                raise ValueError('提醒设置必须为布尔值')
    return result


def validate_apps(config):
    from .catalog import AppCatalog
    from .assistant_notifications import PAYMENT_IDS
    catalog = AppCatalog()
    by_id = {row['id']: row for row in catalog.official + catalog.extra}
    types = {'game': '0003', 'video': '0002', 'social': '0005'}
    for category in ('game', 'video', 'payment', 'social'):
        for app_id in config[category]['appIds']:
            row = by_id.get(app_id)
            valid = app_id in PAYMENT_IDS if category == 'payment' else row and row.get('type') == types[category]
            if not valid:
                raise ValueError('所选应用不属于该提醒分类')


def selected(config, category, app_id):
    settings = config.get(category) or defaults()[category]
    return settings.get('allServices' if category == 'payment' else 'allApps', True) or app_id in settings.get('appIds', [])
