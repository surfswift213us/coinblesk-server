/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.uzh.csg.coinblesk.server.service;

import ch.uzh.csg.coinblesk.server.config.AppConfig;
import com.coinblesk.bitcoin.BitcoinNet;
import com.coinblesk.util.Pair;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.DownloadProgressTracker;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.store.UnreadableWalletException;
import org.bitcoinj.wallet.WalletTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 *
 * @author draft
 */
@Service
public class WalletService {
    
    private final static Logger LOG = LoggerFactory.getLogger(WalletService.class);
    
    @Autowired
    private AppConfig appConfig;
    
    @Autowired
    private KeyService keyService;
    
    @Autowired
    private TransactionService transactionService;
   
    private Wallet wallet;
    
    private BlockChain blockChain;
    
    private PeerGroup peerGroup;
    
    private BlockStore blockStore;
    
    @PostConstruct
    public void init() throws IOException, UnreadableWalletException, BlockStoreException {
        final NetworkParameters params = appConfig.getNetworkParameters();
        //create directory if necessary
        final File directory = appConfig.getConfigDir().getFile();
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new IOException("Could not create directory " + directory.getAbsolutePath());
            }
        }
        //locations
        final File chainFile = new File(directory,  "coinblesk2-"+appConfig.getBitcoinNet()+".spvchain");
        final File walletFile = new File(directory, "coinblesk2-"+appConfig.getBitcoinNet()+".wallet");
        
        if (BitcoinNet.of(appConfig.getBitcoinNet()) == BitcoinNet.UNITTEST) {
            chainFile.delete();
            walletFile.delete();
            LOG.debug("Deleted file {} and {}", chainFile.getName(), walletFile.getName());
        }
        
        if (walletFile.exists()) {
            wallet = Wallet.loadFromFile(walletFile);
            if(!chainFile.exists()) {
                wallet.reset();
            }
        } else {
            //TODO: add keychaingroup for restoring wallet
            wallet = new Wallet(params);
        }
        //TODO: do we nood this?
        //wallet.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        wallet.autosaveToFile(walletFile, 5, TimeUnit.SECONDS, null);
        walletWatchKeys();
        blockStore = new SPVBlockStore(params, chainFile);
        blockChain = new BlockChain(params, blockStore);
        peerGroup = new PeerGroup(params, blockChain);
        
        if (BitcoinNet.of(appConfig.getBitcoinNet()) == BitcoinNet.UNITTEST) {
            peerGroup.addAddress(new PeerAddress(InetAddress.getLocalHost(), params.getPort()));
        } else {
            //peerGroup handles the shutdown for us
            //TODO: connect to real peers
            DnsDiscovery discovery = new DnsDiscovery(params);
            peerGroup.addPeerDiscovery(discovery);
        }
       
        blockChain.addWallet(wallet);
        peerGroup.addWallet(wallet);
        installShutdownHook();
        peerGroup.start();
        final DownloadProgressTracker listener = new DownloadProgressTracker();
        peerGroup.startBlockChainDownload(listener);
        //TODO: add wallet listener, and remove burnedoutputs when confirmed tx 
        // has those outputs (maintenance)
        //also remove the approved tx, once we see them in the blockchain (maintenance)

    }
    
    private void walletWatchKeys() {
        final List<List<ECKey>> all = keyService.all();
        final List<Script> scripts = new ArrayList<>();
        for(List<ECKey> keys:all) {
            final Script script = ScriptBuilder.createP2SHOutputScript(2, keys);
            scripts.add(script);
        }
        wallet.addWatchedScripts(scripts);
    }
    
    public BlockChain blockChain() {
        return blockChain;
    }
    
    public void addWatching(Script script) {
        List<Script> list = new ArrayList<>(1);
        list.add(script);
        wallet.addWatchedScripts(list);
       
    }
    
    public boolean connectBorken(Transaction fullTx, NetworkParameters params) {
        wallet.commitTx(fullTx);
        return true;
        /*Map<Sha256Hash, Transaction> orig = wallet.getTransactionPool(WalletTransaction.Pool.UNSPENT);
        for(TransactionInput input:fullTx.getInputs()) {
            TransactionInput.ConnectionResult result = input.connect(
                    orig, TransactionInput.ConnectMode.DISCONNECT_ON_CONFLICT);
            if(result != TransactionInput.ConnectionResult.SUCCESS) {
                return false;
            }
        }
        return true;*/
    }
    
    @Transactional(readOnly = true)
    public Map<Sha256Hash, Transaction> unspentTransactions(NetworkParameters params) {
        wallet.getBalance();
        Map<Sha256Hash, Transaction> copy = new HashMap<>(wallet.getTransactionPool(WalletTransaction.Pool.UNSPENT));
        //also add approved Tx
        for(Transaction t:copy.values()) {
            LOG.debug("unspent tx: {}", t);
        }
        List<Transaction> approvedTx = transactionService.approvedTx(params);
        for(Transaction t:approvedTx) {
            LOG.debug("adding approved tx, which can be used for spending: {}", t);
            copy.put(t.getHash(), t);
        }
        return copy;
    }
    
    @Transactional(readOnly = true)
    public List<TransactionOutput> unspentOutputs(NetworkParameters params, Address p2shAddress) {
        List<TransactionOutput> retVal = new ArrayList<>();
        List<TransactionOutPoint> spent = new ArrayList<>();
        
        for(Transaction t:transactionService.approvedTx(params)) {
            for(TransactionInput to:t.getInputs()) {
                spent.add(to.getOutpoint());
            }
        }
        
        Map<Sha256Hash, Transaction> unspent = unspentTransactions(params);
        for(Transaction t:unspent.values()) {
            for(TransactionOutput out:t.getOutputs()) {
                if(p2shAddress.equals(out.getAddressFromP2SH(appConfig.getNetworkParameters()))) {
                    if(!spent.contains(out.getOutPointFor()) && out.isAvailableForSpending()) {
                        LOG.debug("this txout is unspent: {}", out);
                        retVal.add(out);
                    } else {
                        LOG.debug("this txout is spent!: {}", out);
                    }
                }
            }
        }
        return retVal;
    }
    
    public long balance(NetworkParameters params, Address p2shAddress) {
        long balance = 0;
        for(TransactionOutput transactionOutput:unspentOutputs(params, p2shAddress)) {
            balance += transactionOutput.getValue().value;
        }
        return balance;
    }
    
    @PreDestroy
    public void shutdown() {
        try {
            if(peerGroup != null && peerGroup.isRunning()) {
                peerGroup.stop();
                peerGroup = null;
            }
        } catch (Exception e) {
            LOG.error("cannot stop peerGroup in shutdown", e);
        }
        try {
            if(blockStore != null) {
                blockStore.close();
                blockStore = null;
            }
        } catch (Exception e) {
            LOG.error("cannot close blockStore in shutdown", e);
        }
        try {
            if(wallet != null) {
                wallet.shutdownAutosaveAndWait();
                wallet = null;
            }
        } catch (Exception e) {
            LOG.error("cannot shutdown wallet in shutdown", e);
        }
    }
    
    private void installShutdownHook(/*final PeerGroup peerGroup, final BlockStore blockStore, 
            final Wallet wallet*/) {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override public void run() {
                shutdown();
            }
        });
    }
    
    

    public int refundLockTime() {
        final int locktime = appConfig.lockTime();
        final int lockPrecision = appConfig.lockPrecision();
        return (((wallet.getLastBlockSeenHeight() + locktime) / lockPrecision) + 1) * lockPrecision;
    }
    
    public int refundEarliestLockTime() {
        final int lockPrecision = appConfig.lockPrecision();
        return ((wallet.getLastBlockSeenHeight() / lockPrecision) + 2) * lockPrecision;
    }

    public void addWatchingOutpointsForRemoval(List<Pair<TransactionOutPoint, Integer>> burned) {
        //TODO: mainteenance during startup        
        //TODO: maintenance cleanup, add listener and do:
        //transactionService.removeConfirmedBurnedOutput(inputsFromConfirmedTransaction);
    }
    
    public void addWatchingTxForRemoval(Transaction approved) {
        //TODO: transaction malleability may spam the system
        //TODO: mainteenance during startup        
        //TODO: maintenance cleanup, add listener and do:
        //transactionService.removeApproved(approved);
    }

    public PeerGroup peerGroup() {
        return peerGroup;
    }
}
