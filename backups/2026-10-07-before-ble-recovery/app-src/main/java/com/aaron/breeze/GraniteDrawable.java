package com.aaron.breeze;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.util.Random;

/** Quiet, deterministic mineral flecks over a deep green stone surface. */
public final class GraniteDrawable extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BitmapShader grain;
    public GraniteDrawable(){
        Bitmap tile=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(tile);Random random=new Random(42);
        for(int i=0;i<2100;i++){
            paint.setColor(i%3==0?0x105f8c6f:0x13030705);
            c.drawCircle(random.nextInt(192),random.nextInt(192),i%11==0?1.2f:.55f,paint);
        }
        grain=new BitmapShader(tile,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT);
        paint.setAlpha(255);
    }
    @Override public void draw(Canvas canvas){
        Rect r=getBounds();paint.setShader(new LinearGradient(0,r.top,r.right,r.bottom,0xff21382a,0xff0e1915,Shader.TileMode.CLAMP));canvas.drawRect(r,paint);
        paint.setShader(grain);canvas.drawRect(r,paint);paint.setShader(null);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
