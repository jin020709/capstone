/* 로그인 · 회원가입 폼 검증 및 제출 */
(function () {
  var U = GP.ui;
  var EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  var PW = /^(?=.*[A-Za-z])(?=.*\d).{8,}$/;

  function setErr(id, msg) { var el = document.getElementById(id + 'Err'); if (el) el.textContent = msg || ''; }
  function clearErrs(form) { U.$all('.error-text', form).forEach(function (e) { e.textContent = ''; }); }
  function nextURL() { var n = U.param('next'); return n && /^[\w.-]+\.html(\?.*)?$/.test(n) ? n : 'index.html'; }

  function onLogin(e) {
    e.preventDefault();
    var f = e.target; clearErrs(f);
    var ok = true;
    if (!EMAIL.test(f.email.value.trim())) { setErr('email', '이메일 형식을 확인해 주세요.'); ok = false; }
    if (f.password.value.length < 8) { setErr('password', '비밀번호는 8자 이상이에요.'); ok = false; }
    if (!ok) return;
    var btn = U.$('button[type=submit]', f); btn.disabled = true;
    GP.api.login({ email: f.email.value.trim(), password: f.password.value })
      .then(function () { location.href = nextURL(); })
      .catch(function (err) { setErr('form', err.message); btn.disabled = false; });
  }

  function onSignup(e) {
    e.preventDefault();
    var f = e.target; clearErrs(f);
    var ok = true;
    if (!EMAIL.test(f.email.value.trim())) { setErr('email', '이메일 형식을 확인해 주세요.'); ok = false; }
    var nick = f.nickname.value.trim();
    if (nick.length < 2 || nick.length > 12) { setErr('nickname', '닉네임은 2~12자로 입력해 주세요.'); ok = false; }
    if (!PW.test(f.password.value)) { setErr('password', '영문과 숫자를 포함해 8자 이상 입력해 주세요.'); ok = false; }
    if (f.password.value !== f.password2.value) { setErr('password2', '비밀번호가 일치하지 않아요.'); ok = false; }
    if (!f.agree.checked) { setErr('agree', '필수 약관에 동의해 주세요.'); ok = false; }
    if (!ok) return;
    var btn = U.$('button[type=submit]', f); btn.disabled = true;
    var body = { email: f.email.value.trim(), nickname: nick, password: f.password.value };
    GP.api.signup(body)
      .then(function () { return GP.api.login({ email: body.email, password: body.password }); })
      .then(function () { U.toast('가입을 환영해요!'); setTimeout(function () { location.href = 'index.html'; }, 600); })
      .catch(function (err) { setErr('form', err.message); btn.disabled = false; });
  }

  document.addEventListener('DOMContentLoaded', function () {
    var lf = document.getElementById('loginForm'), sf = document.getElementById('signupForm');
    if (lf) lf.addEventListener('submit', onLogin);
    if (sf) sf.addEventListener('submit', onSignup);
  });
})();
