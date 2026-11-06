import java.util.ArrayList;
import java.util.List;
import org.geysermc.geyser.entity.type.player.RemoteEquipmentBatch;

public class RemoteEquipmentBatchTest {
    public static void main(String[] args) {
        List<Runnable> queue = new ArrayList<>();
        List<Integer> sent = new ArrayList<>();
        var batch = new RemoteEquipmentBatch(queue::add, sent::add);
        batch.update(RemoteEquipmentBatch.ARMOR);
        batch.update(RemoteEquipmentBatch.ARMOR | RemoteEquipmentBatch.MAIN_HAND);
        batch.update(RemoteEquipmentBatch.OFF_HAND);
        if (queue.size() != 1 || !sent.isEmpty()) throw new AssertionError("must merge updates");
        queue.remove(0).run();
        if (!sent.equals(List.of(7))) throw new AssertionError("all slots must be sent");
        batch.update(RemoteEquipmentBatch.ARMOR);
        batch.invalidate();
        batch.update(RemoteEquipmentBatch.MAIN_HAND);
        queue.remove(0).run();
        if (!sent.equals(List.of(7))) throw new AssertionError("old spawn must not send");
        queue.remove(0).run();
        if (!sent.equals(List.of(7, 2))) throw new AssertionError("new spawn must send");
        batch.update(0);
        if (!queue.isEmpty()) throw new AssertionError("zero update scheduled");
        batch.update(RemoteEquipmentBatch.OFF_HAND);
        queue.remove(0).run();
        if (!sent.equals(List.of(7, 2, 4))) throw new AssertionError("later changes lost");
        System.out.println("RemoteEquipmentBatch tests passed");
    }
}
