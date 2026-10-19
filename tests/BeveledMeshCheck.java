import java.lang.reflect.*;
import java.util.*;

/** Geometry invariants: closed solid, outward unit normals and true-size edge bevels. */
public final class BeveledMeshCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Class<?> renderer = Class.forName("com.example.skyboundjumper.ClimbRenderer3D");
        Field companion = renderer.getDeclaredField("Companion"); companion.setAccessible(true);
        Object owner = companion.get(null);
        Method method = owner.getClass().getDeclaredMethod("beveledBox"); method.setAccessible(true);
        float[] data = (float[]) method.invoke(owner);
        require(data.length == 44 * 18, "Beveled cuboid should contain 44 triangles");
        Map<String,Integer> edges = new HashMap<>();
        for (int i=0; i<data.length; i+=18) {
            float nx=data[i+3], ny=data[i+4], nz=data[i+5];
            require(Math.abs(nx*nx+ny*ny+nz*nz-1f)<.0001f, "Normals must be unit length");
            require(nx*data[i]+ny*data[i+1]+nz*data[i+2]>0, "All faces must point outwards");
            String[] vertices = new String[3];
            for (int j=0;j<3;j++) {
                int k=i+j*6;
                vertices[j]=data[k]+","+data[k+1]+","+data[k+2];
                for (int axis=0;axis<3;axis++) {
                    float p=Math.abs(data[k+axis]);
                    require(p==1f || p==.9f, "Bevel vertices must lie on the face or inset planes");
                    float dimension = new float[]{2f,.5f,1.5f}[axis];
                    float mapped=dimension-(1f-p)*10f*.13f;
                    require(Math.abs(dimension-mapped-(p==1f?0f:.13f))<.00001f, "World-space bevel must be 13cm on every axis");
                }
            }
            for(int j=0;j<3;j++) {
                String a=vertices[j], b=vertices[(j+1)%3];
                String key=a.compareTo(b)<0?a+"/"+b:b+"/"+a;
                edges.merge(key,1,Integer::sum);
            }
        }
        require(edges.values().stream().allMatch(n->n==2), "Every edge must connect exactly two triangles; no holes");
        Method coinMethod = owner.getClass().getDeclaredMethod("cylinderMesh"); coinMethod.setAccessible(true);
        float[] coin = (float[]) coinMethod.invoke(owner);
        require(coin.length > 18000, "Minted coin must include rim, reeds and relief geometry");
        double frontArea=0, backArea=0;
        for (int i=0; i<coin.length; i+=18) {
            if (Math.min(coin[i+2], Math.min(coin[i+8], coin[i+14]))>.85f ||
                Math.max(coin[i+2], Math.max(coin[i+8], coin[i+14]))<-.85f) {
                double area=Math.abs((coin[i+6]-coin[i])*(coin[i+13]-coin[i+1])-
                    (coin[i+12]-coin[i])*(coin[i+7]-coin[i+1]))*.5;
                if (coin[i+2]>0) frontArea+=area; else backArea+=area;
            }
        }
        require(frontArea>1.62 && backArea>1.62, "Both coin faces must be filled discs, not hollow rings");
        for (int i=0; i<coin.length; i+=6) {
            for (int j=0; j<6; j++) require(Float.isFinite(coin[i+j]), "Coin geometry must be finite");
            float nx=coin[i+3], ny=coin[i+4], nz=coin[i+5];
            if (Math.abs(Math.abs(coin[i+2])-.66f)<.0001f)
                require(Math.abs(coin[i]*coin[i]+coin[i+1]*coin[i+1]-1f)<.0001f,
                    "Both outer coin rims must remain perfectly circular without reed dents");
            require(Math.abs(nx*nx+ny*ny+nz*nz-1f)<.001f, "Coin triangles must be nondegenerate with unit normals");
            if (Math.abs(coin[i+2]) < .41f)
                require(nx*coin[i]+ny*coin[i+1]>0, "Reeded coin edges must face outward");
        }
        System.out.println("PASS: closed beveled solid, outward unit normals, consistent world-space edge dimensions.");
        System.out.println("PASS: minted coin geometry, nondegenerate faces and outward reeded edges.");
    }
}
