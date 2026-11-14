#!/bin/bash
set -e
cd "$(dirname "$0")"
JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf gen obj out; mkdir -p gen obj out

python3 - <<'EOF'
from PIL import Image, ImageDraw, ImageFont
F='/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc'
def glyph(size, scale):
    im=Image.new('RGBA',(size,size),(0,0,0,0)); d=ImageDraw.Draw(im)
    f=ImageFont.truetype(F,int(size*scale),index=0)
    b=d.textbbox((0,0),'流',font=f)
    d.text(((size-(b[2]-b[0]))/2-b[0],(size-(b[3]-b[1]))/2-b[1]),'流',font=f,fill='#F4EFE4')
    return im
glyph(432,0.42).save('res/drawable-xxxhdpi/ic_fg.png')
for name,sz in (('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)):
    big=Image.new('RGBA',(sz*4,sz*4),(0,0,0,0)); d=ImageDraw.Draw(big)
    d.rounded_rectangle((sz*0.2,sz*0.2,sz*3.8,sz*3.8),radius=sz*0.7,fill='#C8402F')
    big.alpha_composite(glyph(sz*4,0.62))
    big.resize((sz,sz),Image.LANCZOS).save(f'res/mipmap-{name}/ic_launcher.png')
EOF

aapt package -f -m -J gen -M AndroidManifest.xml -S res -I $JAR
javac -nowarn -source 8 -target 8 -bootclasspath $JAR -d obj $(find src gen -name '*.java') 2>&1 | grep -v -E '^(warning|Note|[0-9]+ warning)' || true
dalvik-exchange --dex --min-sdk-version=23 --output=out/classes.dex obj
aapt package -f -M AndroidManifest.xml -S res -A assets -I $JAR -0 arsc -F out/u.apk
(cd out && aapt add u.apk classes.dex >/dev/null)
zipalign -f 4 out/u.apk out/a.apk
[ -f release.jks ] || keytool -genkeypair -keystore release.jks -storepass nagarecho -keypass nagarecho -alias nagare -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Nagarecho" 2>/dev/null
apksigner sign --ks release.jks --ks-pass pass:nagarecho --key-pass pass:nagarecho --out out/nagarecho-1.1.apk out/a.apk
apksigner verify -v out/nagarecho-1.1.apk | head -5
aapt dump badging out/nagarecho-1.1.apk | head -8
ls -la out/nagarecho-1.1.apk; unzip -l out/nagarecho-1.1.apk
