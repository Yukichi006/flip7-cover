"""地球の雲のテクスチャ（正距円筒図法、4096x2048 のグレースケール）を作る。

使い方: python3 tools/make_clouds.py app/src/main/assets/space
"""
import numpy as np, time, sys
from PIL import Image
S=sys.argv[1]
t0=time.time()
W,H=4096,2048
lon=(np.arange(W,dtype=np.float32)+0.5)/W*2*np.pi-np.pi
lat=np.pi/2-(np.arange(H,dtype=np.float32)+0.5)/H*np.pi
LON,LAT=np.meshgrid(lon,lat)
X=np.cos(LAT)*np.sin(LON); Y=np.sin(LAT); Z=np.cos(LAT)*np.cos(LON)
rng=np.random.default_rng(3)
perm=rng.permutation(256).astype(np.int32); perm=np.concatenate([perm,perm])
grad=rng.random(256).astype(np.float32)
def vnoise(x,y,z):
    xi=np.floor(x).astype(np.int32); yi=np.floor(y).astype(np.int32); zi=np.floor(z).astype(np.int32)
    xf=x-xi; yf=y-yi; zf=z-zi
    u=xf*xf*(3-2*xf); v=yf*yf*(3-2*yf); w=zf*zf*(3-2*zf)
    xi&=255; yi&=255; zi&=255
    def h(a,b,c): return grad[perm[perm[perm[a]+b]+c]&255]
    x1=(xi+1)&255; y1=(yi+1)&255; z1=(zi+1)&255
    a=h(xi,yi,zi)+(h(x1,yi,zi)-h(xi,yi,zi))*u; b=h(xi,y1,zi)+(h(x1,y1,zi)-h(xi,y1,zi))*u
    c=h(xi,yi,z1)+(h(x1,yi,z1)-h(xi,yi,z1))*u; d=h(xi,y1,z1)+(h(x1,y1,z1)-h(xi,y1,z1))*u
    e=a+(b-a)*v; f=c+(d-c)*v
    return e+(f-e)*w
def fbm(x,y,z,oct,base):
    s=np.zeros_like(x); amp=0.5; fr=base
    for _ in range(oct):
        s+=amp*vnoise(x*fr+17.1,y*fr+3.3,z*fr+9.7); amp*=0.5; fr*=2.03
    return s
# 東西に引き伸ばした（緯度方向の周波数を高くした）座標で雲の帯を作る
sy=2.4
wx=fbm(X,Y*sy,Z,5,1.6); wy=fbm(Y*sy,Z,X,5,1.6); wz=fbm(Z,X,Y*sy,5,1.6)
k=1.1
n=fbm(X+k*(wx-0.5),Y*sy+k*(wy-0.5)*0.5,Z+k*(wz-0.5),9,2.6)
detail=fbm(X*9,Y*9*sy,Z*9,5,1.0)
cov=0.40-0.05*np.cos(LAT*2)**8 + 0.04*np.cos(LAT*6)
t=np.clip((n-cov)/0.26,0,1); t=t*t*(3-2*t)
c=t*(0.55+0.6*detail)
c=np.clip(c,0,1)**1.1
Image.fromarray((c*255).astype(np.uint8),'L').save(S+'/clouds.jpg',quality=85)
print('clouds',time.time()-t0, c.mean())
