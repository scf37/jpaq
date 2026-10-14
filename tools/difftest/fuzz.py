"""Random differential fuzzer: builds a random tree, then applies random sequences of add / modify /
extract / list / repack commands (random methods and options) to the C++ and the Java zpaq and
checks after each command that outputs and files are identical.  Usage: fuzz.py SEED [STEPS]
"""
import os, random, subprocess, sys, shutil, struct
DT=os.path.dirname(os.path.abspath(__file__))
W=os.environ.get('WORK', DT+'/build/work')
seed=int(sys.argv[1]); steps=int(sys.argv[2]) if len(sys.argv)>2 else 6
rnd=random.Random(seed)
words=[w for w in open(DT+'/../../cpp/zpaq.pod').read().split()][:5000]

def gen(kind,size):
    if kind=='text':
        out=[];n=0
        while n<size:
            w=rnd.choice(words)+(' ' if rnd.random()<0.9 else '\n'); out.append(w); n+=len(w)
        return ''.join(out).encode()[:size]
    if kind=='rand': return bytes(rnd.getrandbits(8) for _ in range(min(size,1<<14)))*max(1,size//(1<<14)) if rnd.random()<0.3 else os.urandom(size)
    if kind=='zeros': return bytes(size)
    if kind=='pattern':
        p=bytes(rnd.getrandbits(8) for _ in range(rnd.randint(1,300)))
        return (p*(size//len(p)+1))[:size]
    if kind=='x86':
        b=bytearray(os.urandom(size))
        for i in range(0,max(0,size-5),rnd.randint(8,40)):
            b[i]=rnd.choice([0xe8,0xe9]); b[i+4]=rnd.choice([0,255]); 
        return bytes(b)
    if kind=='csv':
        s=''; 
        while len(s)<size: s+='%d,%d,%0.3f\n'%(rnd.randint(0,10**6),rnd.randint(0,99),rnd.random()*1000)
        return s.encode()[:size]
    if kind=='sparse':
        b=bytearray(size)
        for _ in range(rnd.randint(1,20)):
            if size: b[rnd.randrange(size)]=rnd.randrange(256)
        return bytes(b)
kinds=['text','rand','zeros','pattern','x86','csv','sparse']
exts=['txt','bin','exe','dat','csv','','c','TXT','zip']
def rsize():
    r=rnd.random()
    if r<0.1: return 0
    if r<0.4: return rnd.randint(1,5000)
    if r<0.8: return rnd.randint(5000,300000)
    return rnd.randint(300000,3000000)
base_time=1600000000
def mkfile(rel,data,mtime):
    for d in ('R','J'):
        p=os.path.join(W,d,rel)
        os.makedirs(os.path.dirname(p),exist_ok=True)
        open(p,'wb').write(data)
        os.utime(p,(mtime,mtime))
def mkdirs(rel,mtime):
    for d in ('R','J'):
        p=os.path.join(W,d,rel); os.makedirs(p,exist_ok=True)
def rm(rel):
    for d in ('R','J'):
        p=os.path.join(W,d,rel)
        if os.path.isdir(p): shutil.rmtree(p)
        elif os.path.exists(p): os.remove(p)
def listfiles():
    r=[]
    for dp,dn,fn in os.walk(W+'/R/data'):
        for f in fn: r.append(os.path.relpath(os.path.join(dp,f),W+'/R'))
    return sorted(r)
def run(label,*args,fixed=False):
    env=dict(os.environ)
    env['SOURCE_DATE_EPOCH']=str(epoch)
    p=subprocess.run([DT+'/diffrun.sh',label]+list(args),capture_output=True,text=True,env=env)
    out=p.stdout.strip()
    if not out.startswith('OK'):
        print('FAIL seed=%d step=%s: %s\n%s'%(seed,label,' '.join(args),out)); 
        return False
    return True
# init
shutil.rmtree(W+'/R',ignore_errors=True); shutil.rmtree(W+'/J',ignore_errors=True); os.makedirs(W+'/R'); os.makedirs(W+'/J')
for i in range(rnd.randint(3,12)):
    d=rnd.choice(['','a/','a/b/','c/','d d/'])
    rel='data/%s%s%d.%s'%(d,rnd.choice(['f','file','X','Q']),i,rnd.choice(exts))
    mkfile(rel,gen(rnd.choice(kinds),rsize()),base_time+rnd.randint(0,10**6))
mkdirs('data/emptydir',0)
date=20240101000000
epoch=1704067200
mode=rnd.choice(['single','single','multi','index'])
arcname={'single':'z.zpaq','multi':'m???.zpaq','index':'m???.zpaq'}[mode]
methods=['0','1','2','3','4','5','10','11','12','13','14','21','31','x4.1.5.0.3.22','x4.2.4.0.8.25.1c0.0.511','x3.3ci1','x2.0ci1.1.1.1.2am','s','s0']
ok=True
arcs=0
enc = rnd.random()<0.2
keyargs=['-key','pw'] if enc else []
for st in range(steps):
    act=rnd.choices(['add','mod','ext','list','repack'],[5,4,2,1,0.5])[0]
    if act=='add':
        m=rnd.choice(methods)
        if mode=='single':
            args=['a',arcname,'data','-m'+m,'-until',str(date)]+keyargs
        else:
            args=['a',arcname,'data','-m'+m]+keyargs
            if mode=='index': args+=['-index','idx.zpaq']
            if m.startswith('s'): args[args.index('-m'+m)]='-m1'
        date+=1000000
        epoch+=86400*rnd.randint(1,3)
        if rnd.random()<0.3: args+=['-fragment',str(rnd.randint(0,9))]
        if rnd.random()<0.2: args+=['-f']
        if rnd.random()<0.2: args+=['-noattributes']
        if rnd.random()<0.3: args+=['-t'+str(rnd.choice([1,2,3]))]
        if rnd.random()<0.15: args+=['-s1']
        if m.startswith('s') and st>0 and arcs>0: args=args  # may error; fine
        ok&=run('add '+m,*args,fixed=enc)
        arcs+=1
    elif act=='mod':
        fs=listfiles()
        for _ in range(rnd.randint(1,4)):
            c=rnd.choice(['change','append','delete','new','rename','touch'])
            fs=listfiles()
            if c=='new' or not fs:
                rel='data/%s%d.%s'%(rnd.choice(['n/','m/','']),rnd.randint(100,999),rnd.choice(exts))
                mkfile(rel,gen(rnd.choice(kinds),rsize()),base_time+rnd.randint(0,10**7))
            else:
                f=rnd.choice(fs)
                data=open(W+'/R/'+f,'rb').read()
                if c=='change': mkfile(f,gen(rnd.choice(kinds),rsize()),base_time+rnd.randint(0,10**7))
                elif c=='append': mkfile(f,data+gen(rnd.choice(kinds),rnd.randint(1,50000)),base_time+rnd.randint(0,10**7))
                elif c=='delete': rm(f)
                elif c=='touch':
                    tt=base_time+rnd.randint(0,10**7)
                    for d in ('R','J'): os.utime(os.path.join(W,d,f),(tt,tt))
                elif c=='rename':
                    nf='data/mv_%d.%s'%(rnd.randint(0,999),rnd.choice(exts)); mkfile(nf,data,base_time+rnd.randint(0,10**7)); rm(f)
    elif act=='ext' and arcs>0:
        args=['x',arcname,'-to','out%d'%st]+keyargs
        r=rnd.random()
        if r<0.2 and mode!='index': args+=['-until',str(rnd.randint(1,max(1,arcs)))]
        if r>0.8: args+=['-all']
        if rnd.random()<0.2: args+=['-test']
        if rnd.random()<0.2: args+=['-only','*.txt']
        if rnd.random()<0.2: args+=['-not','*/a/*']
        if rnd.random()<0.2: args+=['-f']
        ok&=run('ext',*args,fixed=enc)
    elif act=='list' and arcs>0:
        args=['l',arcname if mode!='index' or rnd.random()<0.5 else 'idx.zpaq']+keyargs
        if rnd.random()<0.4: args+=['-all']
        if rnd.random()<0.3: args+=['-s',str(rnd.choice([-1,3,10]))]
        if rnd.random()<0.3: args+=['data']
        if rnd.random()<0.2: args+=['-f']
        ok&=run('list',*args,fixed=enc)
    elif act=='repack' and arcs>0:
        ok&=run('repack','x',arcname,'-repack','rp%d.zpaq'%st,*(keyargs),fixed=enc) if mode=='single' else True
    if not ok: break
print('seed %d mode %s %s'%(seed,mode,'PASS' if ok else 'FAIL'))
sys.exit(0 if ok else 1)
