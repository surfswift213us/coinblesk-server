/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.uzh.csg.coinblesk.server.controller;

import ch.uzh.csg.coinblesk.server.config.AdminEmail;
import ch.uzh.csg.coinblesk.server.entity.UserAccount;
import ch.uzh.csg.coinblesk.server.service.UserAccountService;
import ch.uzh.csg.coinblesk.server.utils.Pair;
import com.coinblesk.json.Status;
import com.coinblesk.json.StatusTO;
import com.coinblesk.json.UserAccountTO;
import com.coinblesk.responseobject.ExchangeRateTransferObject;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 *
 * @author draft
 */
@RestController
@RequestMapping(value = {"/user", "/u"})
public class UserController {
    
    private final static Logger LOG = LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private JavaMailSender javaMailService;

    @Autowired
    private AdminEmail adminEmail;

    //CRUD for the user
    @RequestMapping(value = {"/create", "/c"}, method = RequestMethod.POST,
            consumes = "application/json; charset=UTF-8",
            produces = "application/json; charset=UTF-8")
    @ResponseBody
    public StatusTO createAccount(@RequestBody UserAccountTO userAccount) {
        LOG.debug("Create account for {}", userAccount.email());
        try {
            //TODO: reactived if deleted flag is set
            Pair<StatusTO, UserAccount> pair = userAccountService.create(userAccount);
            if ((pair.element0().isSuccess()
                    || pair.element0().reason() == StatusTO.Reason.EMAIL_ALREADY_EXISTS_NOT_ACTIVATED)
                    && pair.element1() != null && pair.element1().getEmailToken() != null) {
                SimpleMailMessage smm = new SimpleMailMessage();
                smm.setFrom("bitcoin@csg.uzh.ch");
                smm.setTo(pair.element1().getEmail());
                //TODO: text/layout
                smm.setSubject("Coinblesk Account Activation");
                smm.setText("Please click here: http://host/");
                try {
                    LOG.debug("send email to {}", pair.element1().getEmail());
                    javaMailService.send(smm);
                } catch (Exception e) {
                    LOG.error("Mail send error", e);
                    adminEmail.send("Coinblesk Error", "Unexpected Error: " + e);
                }
            }
            return pair.element0();
        } catch (Exception e) {
            LOG.error("User create error", e);
            return new StatusTO().reason(StatusTO.Reason.SERVER_ERROR).message(e.getMessage());
        }
    }

    @RequestMapping(value = {"/verif/{email}/{token}", "/v/{email}/{token}"}, method = RequestMethod.PATCH)
    @ResponseBody
    public ResponseEntity<String> verifyEmail(@PathVariable(value="email") String email, 
            @PathVariable(value="token") String token, HttpServletRequest request) {
        LOG.debug("Activate account for {}", email);
        StatusTO status = userAccountService.activate(email, token);
        if(!status.isSuccess()) {
            LOG.error("Someone tried a link with an invalid token: {}/{}/{}", email, token, status.reason().name());
            adminEmail.send("Wrong Link?", "Someone tried a link with an invalid token: "+email+" / "+token+ "/"+status.reason().name());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        LOG.debug("Activate account success for {}", email);
        //TODO: text/layout
        return new ResponseEntity<>("Activate account success", HttpStatus.OK);
    }
}

//register / UUID + public key (bt)
//refund / refund tx + sig
//multisig / private (top up)
//transaction 
