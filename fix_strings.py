import os
import re

target_keys = {
    "group_status_proxies_subscription",
    "group_updated",
    "please_update",
    "please_update_force",
    "plugin_exists_but_on_shit_system",
    "profile_requiring_plugin",
    "subscription_traffic"
}

def fix_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    changed = False
    for key in target_keys:
        # Regex to match `<string name="key"` without `formatted="false"`
        pattern = r'(<string\s+name="' + re.escape(key) + r'"(?![^>]*formatted="false")[^>]*>)'
        def repl(match):
            nonlocal changed
            changed = True
            tag = match.group(1)
            return tag.replace('>', ' formatted="false">')
        content = re.sub(pattern, repl, content)
    
    if changed:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Fixed {filepath}")

for root, dirs, files in os.walk('app/src/main/res'):
    for name in files:
        if name == 'strings.xml':
            fix_file(os.path.join(root, name))
