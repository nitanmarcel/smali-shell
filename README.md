# smali-shell

A [jadx-emu](https://github.com/nitanmarcel/jadx-emu) extension: write a smali class, run it against the app loaded in jadx.

## Usage

```smali
.class public Lemu/shell/Main;
.super Ljava/lang/Object;

.method public static run()Ljava/lang/Object;
    .registers 4
    const-string v0, "hello"
    return-object v0
.end method
```

### Calling app methods

```smali
.method public static run()Ljava/lang/Object;
    .registers 2
    const-string v0, "Yf"
    const/4 v1, 0x1
    invoke-static {v0, v1}, Lcom/app/Dec;->xor(Ljava/lang/String;I)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method
```

### Hooks

```smali
.hook Lcom/app/Dec;->xor(Ljava/lang/String;I)Ljava/lang/String; = "patched"
```

```smali
.hook Lcom/app/Dec;->xor(Ljava/lang/String;I)Ljava/lang/String; = Lemu/shell/Main;->logXor(Ljava/lang/String;I)Ljava/lang/String;

.method public static logXor(Ljava/lang/String;I)Ljava/lang/String;
    .registers 4
    invoke-static {p0, p1}, Lcom/app/Dec;->xor(Ljava/lang/String;I)Ljava/lang/String;
    move-result-object v0
    const-string v1, " <- hooked"
    invoke-virtual {v0, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method
```

### Stubs

```smali
.stub Landroid/os/Build;->getRadioVersion = "1.2.3"
.stub Landroid/content/Context;->getPackageName = Lemu/shell/Main;->pkg(Landroid/content/Context;)Ljava/lang/String;
```

### Static fields

```smali
.sput Lcom/app/Dec;->counter:I = 41
.sget Lcom/app/Dec;->counter:I
```

## Directives

| Directive | Effect |
|---|---|
| `.hook Lcls;->m(args)ret = v` | every call to `m` returns `v` |
| `.stub Lcls;->name = v` | framework method `name` (any overload) returns `v` |
| `.sput Lcls;->f:T = v` | write a static field |
| `.sget Lcls;->f:T` | read a static field |
| `.limits <steps> [ms] [depth]` | execution limits |

`v`: `"text"`, `'c'`, `42`, `0x2a`, `42L`, `1.5f`, `true`, `null`, or a static method from the input (gets the original args, receiver first).
