/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.uzh.csg.coinblesk.server.controller;

import ch.uzh.csg.coinblesk.server.config.AppConfig;
import ch.uzh.csg.coinblesk.server.config.BeanConfig;
import ch.uzh.csg.coinblesk.server.config.SecurityConfig;
import ch.uzh.csg.coinblesk.server.service.WalletService;
import ch.uzh.csg.coinblesk.server.utilTest.TestBean;
import ch.uzh.csg.coinblesk.server.utils.Pair;
import com.coinblesk.json.BalanceTO;
import com.coinblesk.json.CompleteSign;
import com.coinblesk.json.KeyTO;
import com.coinblesk.json.PrepareHalfSignTO;
import com.coinblesk.json.RefundP2shTO;
import com.coinblesk.json.RefundTO;
import com.coinblesk.json.SignTO;
import com.github.springtestdbunit.DbUnitTestExecutionListener;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.PrunedException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.testing.FakeTxBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

//http://www.soroushjp.com/2014/12/20/bitcoin-multisig-the-hard-way-understanding-raw-multisignature-bitcoin-transactions/

/**
 *
 * @author draft
 */
@RunWith(SpringJUnit4ClassRunner.class)
@TestExecutionListeners({DependencyInjectionTestExecutionListener.class, TransactionalTestExecutionListener.class,
    DbUnitTestExecutionListener.class})
@ContextConfiguration(classes = {TestBean.class, BeanConfig.class, SecurityConfig.class})
@WebAppConfiguration
public class WalletTest {
    
    public final static long UNIX_TIME_MONTH = 60*60*24*30;
    public final static int LOCK_TIME_MONTHS = 3;
    
    @Autowired
    private WebApplicationContext webAppContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    
    @Autowired
    private AppConfig appConfig;
    
    @Autowired
    private WalletService walletService;
    
    private static MockMvc mockMvc;
    
    private static final Gson GSON;
    
    static {
         GSON = new GsonBuilder().create();
    }
    
    @Before
    public void setUp() {
         mockMvc = MockMvcBuilders.webAppContextSetup(webAppContext).addFilter(springSecurityFilterChain).build();   
    }
    
    @Test
    public void testTopup() throws Exception {
        //register with good pubilc key
        ECKey ecKeyClient = new ECKey();
        String clientPublicKey = Base64.getEncoder().encodeToString(ecKeyClient.getPubKey());
        KeyTO keyTO = new KeyTO().publicKey(clientPublicKey);
        MvcResult res = mockMvc.perform(post("/p/x").secure(true).contentType(MediaType.APPLICATION_JSON).content(GSON.toJson(keyTO))).andExpect(status().isOk()).andReturn();
        KeyTO status = GSON.fromJson(res.getResponse().getContentAsString(), KeyTO.class);
        Assert.assertEquals(true, status.isSuccess());
        Assert.assertNotNull(status.publicKey());
        
        res = mockMvc.perform(get("/p/b").secure(true).contentType(MediaType.APPLICATION_JSON).content(GSON.toJson(keyTO))).andExpect(status().isOk()).andReturn();
        BalanceTO balance = GSON.fromJson(res.getResponse().getContentAsString(), BalanceTO.class);
        Assert.assertEquals("0", balance.balance());
        
        //
        final List<ECKey> keys = new ArrayList<>();
        keys.add(ecKeyClient);
        keys.add(ECKey.fromPublicOnly(Base64.getDecoder().decode(status.publicKey())));
        final Script script = ScriptBuilder.createP2SHOutputScript(2, keys);
        sendFakeCoins(Coin.MICROCOIN, script.getToAddress(appConfig.getNetworkParameters()));
        
        res = mockMvc.perform(get("/p/b").secure(true).contentType(MediaType.APPLICATION_JSON).content(GSON.toJson(keyTO))).andExpect(status().isOk()).andReturn();
        balance = GSON.fromJson(res.getResponse().getContentAsString(), BalanceTO.class);
        Assert.assertEquals("0.000001", balance.balance());
        
        //pay to multisig address in unittest
    }
    
    @Test
    public void testRefund() throws Exception {
        //register with good pubilc key
        ECKey ecKeyClient = new ECKey();
        String clientPublicKey = Base64.getEncoder().encodeToString(ecKeyClient.getPubKey());
        KeyTO keyTO = new KeyTO().publicKey(clientPublicKey);
        MvcResult res = mockMvc.perform(post("/p/x").secure(true).contentType(MediaType.APPLICATION_JSON).content(GSON.toJson(keyTO))).andExpect(status().isOk()).andReturn();
        KeyTO status = GSON.fromJson(res.getResponse().getContentAsString(), KeyTO.class);
        Assert.assertEquals(true, status.isSuccess());
        Assert.assertNotNull(status.publicKey());
        
        //create funding/toput tx
        final List<ECKey> keys = new ArrayList<>();
        keys.add(ecKeyClient);
        keys.add(ECKey.fromPublicOnly(Base64.getDecoder().decode(status.publicKey())));
        final Script redeemScript = ScriptBuilder.createP2SHOutputScript(2, keys);
        Transaction tx = FakeTxBuilder.createFakeTx(appConfig.getNetworkParameters(), Coin.MICROCOIN, redeemScript.getToAddress(appConfig.getNetworkParameters()));
        Assert.assertTrue(tx.getOutputs().get(0).getScriptPubKey().isPayToScriptHash());
        Assert.assertTrue(tx.getOutputs().get(1).getScriptPubKey().isSentToAddress());
        //create refund tx based on the topup
        ECKey refundAddress = new ECKey();
        Pair<Transaction,List<TransactionSignature>> refund = generateRefundTransaction(tx.getOutputs(), refundAddress, redeemScript, ecKeyClient);
        RefundTO rto = new RefundTO();
        rto.clientPublicKey(clientPublicKey);
        rto.refundTransaction(Base64.getEncoder().encodeToString(refund.element0().unsafeBitcoinSerialize()));
        List<RefundTO.ClientSig> l = new ArrayList<>();
        rto.clientSignatureR(l);
        for(TransactionSignature ts:refund.element1()) {
            l.add(new RefundTO.ClientSig().clientSignatureR(ts.r.toString()).clientSignatureS(ts.s.toString()));
        }
        res = mockMvc.perform(post("/p/r").secure(true).contentType(MediaType.APPLICATION_JSON).content(GSON.toJson(rto))).andExpect(status().isOk()).andReturn();
        RefundTO refundRet = GSON.fromJson(res.getResponse().getContentAsString(), RefundTO.class);
        Transaction fullRefund = new Transaction(appConfig.getNetworkParameters(), Base64.getDecoder().decode(refundRet.refundTransaction()));
        //todo test tx
    }
    
    @Test
    public void testSameRefundTx() throws Exception {
        final Transaction tx1 = new Transaction(appConfig.getNetworkParameters());
        final long unixTime1 = System.currentTimeMillis()/ 1000L;
        final long lockTime1 = unixTime1 + (LOCK_TIME_MONTHS * UNIX_TIME_MONTH);
        tx1.setLockTime(lockTime1);
        
        Thread.sleep(1100);
        
        final Transaction tx2 = new Transaction(appConfig.getNetworkParameters());
        final long unixTime2 = System.currentTimeMillis()/ 1000L;
        final long lockTime2 = unixTime2 + (LOCK_TIME_MONTHS * UNIX_TIME_MONTH);
        tx2.setLockTime(lockTime2);
        
        Assert.assertFalse(tx1.equals(tx2));
    }
    
    @Test
    public void testSameRefundTx2() throws Exception {
        final Transaction tx1 = new Transaction(appConfig.getNetworkParameters());
        final long unixTime1 = ((System.currentTimeMillis() / 1000L) / (10 * 60)) * (10 * 60);
        final long lockTime1 = unixTime1 + (LOCK_TIME_MONTHS * UNIX_TIME_MONTH);
        tx1.setLockTime(lockTime1);
        
        Thread.sleep(1100);
        
        final Transaction tx2 = new Transaction(appConfig.getNetworkParameters());
        final long unixTime2 = ((System.currentTimeMillis() / 1000L) / (10 * 60)) * (10 * 60);
        final long lockTime2 = unixTime2 + (LOCK_TIME_MONTHS * UNIX_TIME_MONTH);
        tx2.setLockTime(lockTime2);
        
        Assert.assertTrue(tx1.equals(tx2));
    }
    
    
    
    @Test
    public void testRequestBTC() throws Exception {
        //Merchant
        ECKey ecKeyMerchant = new ECKey();
        ECKey ecKeyServerMerchant = register(ecKeyMerchant);
        Script redeemScriptServerMerchant = createRedeemScript(ecKeyMerchant, ecKeyServerMerchant);
        Address p2shAddressMerchant = redeemScriptServerMerchant.getToAddress(appConfig.getNetworkParameters());
        //Client
        ECKey ecKeyClient = new ECKey();
        ECKey ecKeyServerClient = register(ecKeyClient);
        Script redeemScriptServerClient = createRedeemScript(ecKeyClient, ecKeyServerClient);
        Address p2shAddressClient = redeemScriptServerClient.getToAddress(appConfig.getNetworkParameters());
        //preload client
        Transaction funding = sendFakeCoins(Coin.valueOf(10000), p2shAddressClient);
        
        //Merchant requests 1000s to address p2shAddressTo, sends to client
        Coin amountToRequest = Coin.valueOf(1000);
        
        //client sends ok, merchant forwards to server TODO: signatures or encryption
        PrepareHalfSignTO prepareHalfSignTO = new PrepareHalfSignTO();
        prepareHalfSignTO.amountToSpend(amountToRequest.longValue());
        prepareHalfSignTO.clientPublicKey(ecKeyClient.getPubKey());
        prepareHalfSignTO.p2shAddress(p2shAddressMerchant.getHash160());
        //
        MvcResult res = mockMvc.perform(post("/p/p").secure(true).
                contentType(MediaType.APPLICATION_JSON).content(GSON.toJson(prepareHalfSignTO))).andExpect(status().isOk()).andReturn();
        PrepareHalfSignTO status = GSON.fromJson(res.getResponse().getContentAsString(), PrepareHalfSignTO.class);
        Transaction halfSignedTx = new Transaction(appConfig.getNetworkParameters(), status.halfSignedTransaction());
        
        //now, we give the sigs to the client
        List<TransactionSignature> sigs = deserialize(status.signatures());
                
        
        // those sigs go to the client
        //client rebuilds the tx
        List<TransactionOutput> outputs = funding.getOutputs();
        Pair<Transaction,List<TransactionSignature>> fullTxPair = PaymentController.createHalfSignedTx(appConfig.getNetworkParameters(), outputs, 
                p2shAddressClient, p2shAddressMerchant, amountToRequest, redeemScriptServerClient, ecKeyClient, sigs).element1();
        //now we have the full tx, based on that create the refund tx
        Pair<Transaction,Pair<List<TransactionOutPoint>,List<TransactionSignature>>> pair = 
                generateRefundTransaction2(find(fullTxPair.element0().getOutputs(), p2shAddressClient), 
                        ecKeyClient.toAddress(appConfig.getNetworkParameters()), redeemScriptServerClient, ecKeyClient);
        sigs = pair.element1().element1();
        
        Transaction halfSignedRefundTx = pair.element0();
        //this info goes to the server
        //server now builds the full refund and signs it, send back sigs
        RefundP2shTO refundP2shTO = new RefundP2shTO();
        refundP2shTO.clientPublicKey(Base64.getEncoder().encodeToString(ecKeyClient.getPubKey()));
        refundP2shTO.refundTransaction(serialize(pair.element1().element0()));
        refundP2shTO.refundSignatures(serialize2(pair.element1().element1()));
        
        res = mockMvc.perform(post("/p/f").secure(true).
                contentType(MediaType.APPLICATION_JSON).content(GSON.toJson(refundP2shTO))).andExpect(status().isOk()).andReturn();
        RefundP2shTO status2 = GSON.fromJson(res.getResponse().getContentAsString(), RefundP2shTO.class);
        Assert.assertTrue(status2.isSuccess());
        //we now have the full refund tx and including the sigs, for convenincie, the sigs are in the json as well
        //send the sigs to the client
        Transaction refundClient = signFully(halfSignedRefundTx, pair.element1().element1(), 
                deserialize(status2.refundSignatures()), redeemScriptServerClient);
        Transaction refundServer = new Transaction(appConfig.getNetworkParameters(), status2.fullRefundTransaction());
        Assert.assertEquals(refundClient, refundServer);
        
        //now we are ok, send the sigs to the sever so that the server can create the full tx, the merchant can build this as well.
        Transaction fullTxMerchant = signFully(halfSignedTx, fullTxPair.element1(), sigs, redeemScriptServerClient);
        CompleteSign cs = new CompleteSign();
        cs.clientPublicKey(ecKeyClient.getPubKey());
        cs.fullSignedTransaction(fullTxMerchant.unsafeBitcoinSerialize());
        res = mockMvc.perform(post("/p/s").secure(true).
                contentType(MediaType.APPLICATION_JSON).content(GSON.toJson(cs))).andExpect(status().isOk()).andReturn();
        CompleteSign status3 = GSON.fromJson(res.getResponse().getContentAsString(), CompleteSign.class);
        Assert.assertTrue(status3.isSuccess());
        sendFakeBroadcast(fullTxMerchant);
        
        System.out.println("client: "+fullTxMerchant);
        for(TransactionInput ti:fullTxMerchant.getInputs()) {
            System.out.println("clientI11: "+bytesToHex(ti.bitcoinSerialize()));
        }
        for(TransactionOutput to:fullTxMerchant.getOutputs()) {
            System.out.println("clientO11: "+bytesToHex(to.bitcoinSerialize()));
        }
        
        System.out.println("server: "+fullTxPair.element0());
        for(TransactionInput ti:fullTxPair.element0().getInputs()) {
            System.out.println("serverI11: "+bytesToHex(ti.bitcoinSerialize()));
        }
        for(TransactionOutput to:fullTxPair.element0().getOutputs()) {
            System.out.println("serverO11: "+bytesToHex(to.bitcoinSerialize()));
        }
        
        Assert.assertEquals(fullTxMerchant, fullTxPair.element0());
        //check balance
    }
    
    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for ( int j = 0; j < bytes.length; j++ ) {
        int v = bytes[j] & 0xFF;
        hexChars[j * 2] = hexArray[v >>> 4];
        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
    }
    return new String(hexChars);
}
    
    private List<TransactionOutput> find(List<TransactionOutput> input, Address to) {
        List<TransactionOutput> retVal = new ArrayList<>();
        for(TransactionOutput tout:input) {
            if(tout.getAddressFromP2SH(appConfig.getNetworkParameters()).equals(to)) {
                retVal.add(tout);
            }
        }
        return retVal;
    }
    
    private ECKey register(ECKey ecKeyClient) throws Exception {
        KeyTO keyTO = new KeyTO().publicKey(Base64.getEncoder().encodeToString(ecKeyClient.getPubKey()));
        MvcResult res = mockMvc.perform(post("/p/x").secure(true).
                contentType(MediaType.APPLICATION_JSON).content(GSON.toJson(keyTO))).andExpect(status().isOk()).andReturn();
        KeyTO status = GSON.fromJson(res.getResponse().getContentAsString(), KeyTO.class);
        return ECKey.fromPublicOnly(Base64.getDecoder().decode(status.publicKey())); 
    }
    
    private Script createRedeemScript(ECKey ecKeyClient, ECKey ecKeyServer) {
        final List<ECKey> keys = new ArrayList<>();
        keys.add(ecKeyClient);
        keys.add(ecKeyServer);
        final Script redeemScript = ScriptBuilder.createP2SHOutputScript(2, keys);
        return redeemScript;
    }
    
    private Transaction sendFakeBroadcast(Transaction tx) throws BlockStoreException, VerificationException, PrunedException, InterruptedException {
        BlockChain chain = walletService.blockChain();
        Block block = FakeTxBuilder.makeSolvedTestBlock(chain.getBlockStore().getChainHead().getHeader(), tx);
        chain.add(block);
        Thread.sleep(250);
        return tx;
    }
    
    private Transaction sendFakeCoins(Coin amount, Address to) throws VerificationException, PrunedException, BlockStoreException, InterruptedException {
        Transaction tx = FakeTxBuilder.createFakeTx(appConfig.getNetworkParameters(), amount, to);
        BlockChain chain = walletService.blockChain();
        Block block = FakeTxBuilder.makeSolvedTestBlock(chain.getBlockStore().getChainHead().getHeader(), tx);
        chain.add(block);
        Thread.sleep(250);
        return tx;
    }
    
    private Pair<Transaction,List<TransactionSignature>> generateRefundTransaction(List<TransactionOutput> outputs, ECKey publicClientKey, Script redeemScript, ECKey ecKeyClient) {
        final Transaction refundTransaction = new Transaction(appConfig.getNetworkParameters());
        final long unixTime = ((System.currentTimeMillis() / 1000L) / (10 * 60)) * (10 * 60);
        final long lockTime = unixTime + (LOCK_TIME_MONTHS * UNIX_TIME_MONTH);
        Coin remainingAmount = Coin.ZERO;
        for(TransactionOutput output:outputs) {
            Address a = output.getAddressFromP2SH(appConfig.getNetworkParameters());
            if(a!=null && a.equals(redeemScript.getToAddress(appConfig.getNetworkParameters()))) {
                refundTransaction.addInput(output);
                remainingAmount = remainingAmount.add(output.getValue());
            }
        }
        
        remainingAmount = remainingAmount.subtract(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE);
        refundTransaction.addOutput(remainingAmount, publicClientKey);
        refundTransaction.setLockTime(lockTime);
        //sign input, they are multisig
        List<TransactionSignature> clientSigs = new ArrayList<>();
        for(int i=0;i<refundTransaction.getInputs().size();i++) {
            final Sha256Hash sighash = refundTransaction.hashForSignature(i, redeemScript, Transaction.SigHash.ALL, false);
            final TransactionSignature clientSignature = new TransactionSignature(ecKeyClient.sign(sighash), Transaction.SigHash.ALL, false);
            clientSigs.add(clientSignature);
        }
        

        return new Pair<>(refundTransaction, clientSigs);
    }
    
    private Pair<Transaction,Pair<List<TransactionOutPoint>,List<TransactionSignature>>> generateRefundTransaction2(List<TransactionOutput> outputs, Address refund, Script redeemScript, ECKey ecKeyClient) {
        final Transaction refundTransaction = new Transaction(appConfig.getNetworkParameters());
        final long unixTime = ((System.currentTimeMillis() / 1000L) / (10 * 60)) * (10 * 60);
        final long lockTime = unixTime + (LOCK_TIME_MONTHS * UNIX_TIME_MONTH);
        Coin remainingAmount = Coin.ZERO;
        for(TransactionOutput output:outputs) {
            Address a = output.getAddressFromP2SH(appConfig.getNetworkParameters());
            if(a!=null && a.equals(redeemScript.getToAddress(appConfig.getNetworkParameters()))) {
                refundTransaction.addInput(output);
                remainingAmount = remainingAmount.add(output.getValue());
            }
        }
        
        remainingAmount = remainingAmount.subtract(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE);
        refundTransaction.addOutput(remainingAmount, refund);
        refundTransaction.setLockTime(lockTime);
        //sign input, they are multisig
        List<TransactionSignature> clientSigs = new ArrayList<>();
        List<TransactionOutPoint> tpoint = new ArrayList<>();
        for(int i=0;i<refundTransaction.getInputs().size();i++) {
            final Sha256Hash sighash = refundTransaction.hashForSignature(i, redeemScript, Transaction.SigHash.ALL, false);
            final TransactionSignature clientSignature = new TransactionSignature(ecKeyClient.sign(sighash), Transaction.SigHash.ALL, false);
            clientSigs.add(clientSignature);
            tpoint.add(refundTransaction.getInputs().get(i).getOutpoint());
        }
        
        storedHalfRefundTx.put(ecKeyClient, refundTransaction);
        return new Pair<>(refundTransaction,new Pair<>(tpoint, clientSigs));
    }
    
     private static Map<ECKey, Transaction> storedHalfRefundTx = new HashMap<>();

    private List<byte[]> serialize(List<TransactionOutPoint> points) {
        List<byte[]> retVal = new ArrayList<>();
        for(TransactionOutPoint top:points) {
            retVal.add(top.unsafeBitcoinSerialize());
        }
        return retVal;
    }

    private List<RefundP2shTO.TxSig> serialize2(List<TransactionSignature> element1) {
        List<RefundP2shTO.TxSig> retVal = new ArrayList<>();
        for(TransactionSignature sig:element1) {
            retVal.add(new RefundP2shTO.TxSig().clientSignatureR(sig.r.toString()).clientSignatureS(sig.s.toString()));
        }
        return retVal;
    }
    
    private List<TransactionSignature> deserialize(List<RefundP2shTO.TxSig> refundSignatures) {
        List<TransactionSignature> retVal = new ArrayList<>();
        for(RefundP2shTO.TxSig b:refundSignatures) {
            retVal.add(new TransactionSignature(new BigInteger(b.clientSignatureR()), new BigInteger(b.clientSignatureS())));
        }
        return retVal;
    }

    private Transaction signFully(Transaction tx, 
            List<TransactionSignature> clientSigs, List<TransactionSignature> serverSigs, Script redeemScript) {
        for(int i=0;i<tx.getInputs().size();i++) {
            List<TransactionSignature> l = new ArrayList<>(2);
            l.add(clientSigs.get(i));
            l.add(serverSigs.get(i));
            final Script refundTransactionInputScript = ScriptBuilder.createP2SHMultiSigInputScript(l, redeemScript);
            tx.getInput(i).setScriptSig(refundTransactionInputScript);
        }    
        return tx;   
    }
}
