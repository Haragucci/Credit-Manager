package op.creditmanager.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerCreditData {

    private String playerName;
    private List<UUID> creditsAsDebtor;
    private List<UUID> creditsAsCreditor;

    public PlayerCreditData() {
        this.creditsAsDebtor = new ArrayList<>();
        this.creditsAsCreditor = new ArrayList<>();
    }

    public PlayerCreditData(String playerName) {
        this.playerName = playerName;
        this.creditsAsDebtor = new ArrayList<>();
        this.creditsAsCreditor = new ArrayList<>();
    }

    public void addDebtorCredit(UUID creditId) {
        if (!creditsAsDebtor.contains(creditId)) {
            creditsAsDebtor.add(creditId);
        }
    }

    public void addCreditorCredit(UUID creditId) {
        if (!creditsAsCreditor.contains(creditId)) {
            creditsAsCreditor.add(creditId);
        }
    }

    public void removeDebtorCredit(UUID creditId) {
        creditsAsDebtor.remove(creditId);
    }

    public void removeCreditorCredit(UUID creditId) {
        creditsAsCreditor.remove(creditId);
    }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public List<UUID> getCreditsAsDebtor() { return creditsAsDebtor; }
    public void setCreditsAsDebtor(List<UUID> creditsAsDebtor) { this.creditsAsDebtor = creditsAsDebtor; }

    public List<UUID> getCreditsAsCreditor() { return creditsAsCreditor; }
    public void setCreditsAsCreditor(List<UUID> creditsAsCreditor) { this.creditsAsCreditor = creditsAsCreditor; }
}
