from pathlib import Path
p=Path('tools/shinya_hub/assets/hub-ui/router-tools.html');s=p.read_text(encoding='utf8')
css='''.sheet:not(.menu){max-height:calc(100dvh - 24px)}.sheet h2{font-size:16px;padding:14px 16px 9px}.form-body{padding:0 16px 2px}.field{font-size:11px;margin:7px 0}.field input:not([type=checkbox]),.choice{font-size:13px;min-height:36px;padding:8px 12px;margin-top:4px;border-radius:10px}.checks{gap:8px;margin:5px 0}.checks label,.checks label:has(input:checked){border:0;background:transparent;min-height:28px;padding:3px 0;border-radius:0;justify-content:flex-start;font-size:12px}.checks input,.state input{-webkit-appearance:none;appearance:none;position:relative;width:16px;height:16px;min-height:16px;border:1.5px solid #a1a7ad;border-radius:3px;padding:0;margin:0;outline:0;box-shadow:none;background:#fff;flex-shrink:0}.checks input:checked,.state input:checked{background:var(--teal);border-color:var(--teal)}.checks input:checked:after,.state input:checked:after{content:'';position:absolute;left:4px;top:1px;width:4px;height:8px;border:solid #fff;border-width:0 2px 2px 0;transform:rotate(45deg)}.checks input:focus,.state input:focus{outline:0;box-shadow:none}.sheet .actions{padding:10px 14px}.sheet .actions button{font-size:13px;min-height:36px;padding:8px 12px}.sheet .state{margin:8px 0;font-size:12px}.form-note{font-size:10px;margin:4px 0}.sheet form{flex:1;overflow:hidden}
'''
s=s.replace('</style>',css+'</style>');p.write_text(s,encoding='utf8')
p=Path('tools/shinya_hub/HubDiagnosisView.java');s=p.read_text(encoding='utf8')
s=s.replace('private boolean active=false,busy=false;','private boolean active=false,busy=false,layoutQueued=false;')
s=s.replace('super.requestLayout();\n        post(measureAndLayout);','super.requestLayout();\n        if(!layoutQueued){layoutQueued=true;post(measureAndLayout);}')
s=s.replace('''                layout(getLeft(),getTop(),getRight(),getBottom());
            }
        }
    };''','''                layout(getLeft(),getTop(),getRight(),getBottom());
            }
            layoutQueued=false;
        }
    };''')
s=s.replace('''    private void relayout(){
        post(measureAndLayout);
    }''','''    private void relayout(){
        if(!layoutQueued){layoutQueued=true;post(measureAndLayout);}
    }''')
p.write_text(s,encoding='utf8')
print('Compact form controls and checkboxes; diagnostic layout scheduling coalesced')
